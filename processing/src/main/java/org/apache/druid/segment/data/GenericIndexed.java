/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.data;

import com.google.common.primitives.Ints;
import org.apache.druid.collections.ResourceHolder;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.common.utils.SerializerUtils;
import org.apache.druid.io.Channels;
import org.apache.druid.java.util.common.ByteBufferUtils;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.io.smoosh.FileSmoosher;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.serde.MetaSerdeHelper;
import org.apache.druid.segment.serde.Serializer;
import org.apache.druid.segment.writeout.HeapByteBufferWriteOutBytes;
import org.apache.druid.utils.CloseableUtils;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Iterator;

/**
 * A generic, flat storage mechanism.  Use static methods fromArray() or fromIterable() to construct.  If input
 * is sorted, supports binary search index lookups.  If input is not sorted, only supports array-like index lookups.
 * <p>
 * V1 Storage Format:
 * <p>
 * byte 1: version (0x1)
 * byte 2 == 0x1 =>; allowReverseLookup
 * bytes 3-6 =>; numBytesUsed
 * bytes 7-10 =>; numElements
 * bytes 10-((numElements * 4) + 10): integers representing *end* offsets of byte serialized values
 * bytes ((numElements * 4) + 10)-(numBytesUsed + 2): 4-byte integer representing length of value, followed by bytes
 * for value. Length of value stored has no meaning, if next offset is strictly greater than the current offset,
 * and if they are the same, -1 at this field means null, and 0 at this field means some object
 * (potentially non-null - e. g. in the string case, that is serialized as an empty sequence of bytes).
 * <p>
 * V2 Storage Format
 * Meta, header and value files are separate and header file stored in native endian byte order.
 * Meta File:
 * byte 1: version (0x2)
 * byte 2 == 0x1 =>; allowReverseLookup
 * bytes 3-6: numberOfElementsPerValueFile expressed as power of 2. That means all the value files contains same
 * number of items except last value file and may have fewer elements.
 * bytes 7-10 =>; numElements
 * bytes 11-14 =>; columnNameLength
 * bytes 15-columnNameLength =>; columnName
 * <p>
 * Header file name is identified as: StringUtils.format("%s_header", columnName)
 * value files are identified as: StringUtils.format("%s_value_%d", columnName, fileNumber)
 * number of value files == numElements/numberOfElementsPerValueFile
 *
 * The version {@link EncodedStringDictionaryWriter#VERSION} is reserved and must never be specified as the
 * {@link GenericIndexed} version byte, else it will interfere with string column deserialization.
 *
 * @see GenericIndexedWriter
 */
public class GenericIndexed<T> implements CloseableIndexed<T>, Serializer
{
  static final byte VERSION_ONE = 0x1;
  static final byte VERSION_TWO = 0x2;
  static final byte REVERSE_LOOKUP_ALLOWED = 0x1;
  static final byte REVERSE_LOOKUP_DISALLOWED = 0x0;

  static final int NULL_VALUE_SIZE_MARKER = -1;

  private static final MetaSerdeHelper<GenericIndexed> META_SERDE_HELPER = MetaSerdeHelper
      .firstWriteByte((GenericIndexed x) -> VERSION_ONE)
      .writeByte(x -> x.allowReverseLookup ? REVERSE_LOOKUP_ALLOWED : REVERSE_LOOKUP_DISALLOWED)
      .writeInt(x -> Ints.checkedCast(x.theBuffer.remaining() + (long) Integer.BYTES))
      .writeInt(x -> x.size);

  private static final SerializerUtils SERIALIZER_UTILS = new SerializerUtils();

  /**
   * An ObjectStrategy that returns a big-endian ByteBuffer pointing to original data.
   *
   * The returned ByteBuffer is a fresh read-only instance, so it is OK for callers to modify its position, limit, etc.
   * However, it does point to the original data, so callers must take care not to use it if the original data may
   * have been freed.
   *
   * The compare method of this instance uses {@link StringUtils#compareUtf8UsingJavaStringOrdering(byte[], byte[])}
   * so that behavior is consistent with {@link #STRING_STRATEGY}.
   */
  public static final ObjectStrategy<ByteBuffer> UTF8_STRATEGY = new ObjectStrategy<ByteBuffer>()
  {
    @Override
    public Class<ByteBuffer> getClazz()
    {
      return ByteBuffer.class;
    }

    @Override
    public ByteBuffer fromByteBuffer(final ByteBuffer buffer, final int numBytes)
    {
      final ByteBuffer dup = buffer.asReadOnlyBuffer();
      dup.limit(buffer.position() + numBytes);
      return dup;
    }

    @Override
    @Nullable
    public byte[] toBytes(@Nullable ByteBuffer buf)
    {
      if (buf == null) {
        return null;
      }

      // This method doesn't have javadocs and I'm not sure if it is OK to modify the "val" argument. Copy defensively.
      final ByteBuffer dup = buf.duplicate();
      final byte[] bytes = new byte[dup.remaining()];
      dup.get(bytes);
      return bytes;
    }

    @Override
    public int compare(@Nullable ByteBuffer o1, @Nullable ByteBuffer o2)
    {
      return ByteBufferUtils.utf8Comparator().compare(o1, o2);
    }
  };

  public static final ObjectStrategy<String> STRING_STRATEGY = new ObjectStrategy<String>()
  {
    @Override
    public Class<String> getClazz()
    {
      return String.class;
    }

    @Override
    public String fromByteBuffer(final ByteBuffer buffer, final int numBytes)
    {
      return StringUtils.fromUtf8(buffer, numBytes);
    }

    @Override
    @Nullable
    public byte[] toBytes(@Nullable String val)
    {
      return StringUtils.toUtf8Nullable(NullHandling.nullToEmptyIfNeeded(val));
    }

    @Override
    public int compare(String o1, String o2)
    {
      return Comparators.<String>naturalNullsFirst().compare(o1, o2);
    }
  };

  public static <T> GenericIndexed<T> read(ByteBuffer buffer, ObjectStrategy<T> strategy)
  {
    byte versionFromBuffer = buffer.get();

    if (VERSION_ONE == versionFromBuffer) {
      return createGenericIndexedVersionOne(buffer, strategy);
    } else if (VERSION_TWO == versionFromBuffer) {
      throw new IAE(
          "use read(ByteBuffer buffer, ObjectStrategy<T> strategy, SmooshedFileMapper fileMapper)"
          + " to read version 2 indexed."
      );
    }
    throw new IAE("Unknown version[%d]", (int) versionFromBuffer);
  }

  public static <T> GenericIndexed<T> read(ByteBuffer buffer, ObjectStrategy<T> strategy, SmooshedFileMapper fileMapper)
  {
    byte versionFromBuffer = buffer.get();

    if (VERSION_ONE == versionFromBuffer) {
      return createGenericIndexedVersionOne(buffer, strategy);
    } else if (VERSION_TWO == versionFromBuffer) {
      return createGenericIndexedVersionTwo(buffer, strategy, fileMapper);
    }

    throw new IAE("Unknown version [%s]", versionFromBuffer);
  }

  public static <T> GenericIndexed<T> fromArray(T[] objects, ObjectStrategy<T> strategy)
  {
    return fromIterable(Arrays.asList(objects), strategy);
  }

  public static GenericIndexed<ResourceHolder<ByteBuffer>> ofCompressedByteBuffers(
      Iterable<ByteBuffer> buffers,
      CompressionStrategy compression,
      int bufferSize,
      ByteOrder order,
      Closer closer
  )
  {
    return fromIterableVersionOne(
        buffers,
        GenericIndexedWriter.compressedByteBuffersWriteObjectStrategy(compression, bufferSize, closer),
        false,
        new DecompressingByteBufferObjectStrategy(order, compression)
    );
  }

  public static <T> GenericIndexed<T> fromIterable(Iterable<T> objectsIterable, ObjectStrategy<T> strategy)
  {
    return fromIterableVersionOne(objectsIterable, strategy, strategy.canCompare(), strategy);
  }

  static int getNumberOfFilesRequired(int bagSize, long numWritten)
  {
    int numberOfFilesRequired = (int) (numWritten / bagSize);
    if ((numWritten % bagSize) != 0) {
      numberOfFilesRequired += 1;
    }
    return numberOfFilesRequired;
  }


  private final boolean versionOne;

  private final ObjectStrategy<T> strategy;
  private final boolean allowReverseLookup;
  private final int size;
  private final ByteBuffer headerBuffer;

  private final ByteBuffer firstValueBuffer;

  private final ByteBuffer[] valueBuffers;
  private int logBaseTwoOfElementsPerValueFile;
  private int relativeIndexMask;

  @Nullable
  private final ByteBuffer theBuffer;

  /**
   * Constructor for version one.
   */
  GenericIndexed(
      ByteBuffer buffer,
      ObjectStrategy<T> strategy,
      boolean allowReverseLookup
  )
  {
    this.versionOne = true;

    this.theBuffer = buffer;
    this.strategy = strategy;
    this.allowReverseLookup = allowReverseLookup;
    size = theBuffer.getInt();

    int indexOffset = theBuffer.position();
    int valuesOffset = theBuffer.position() + size * Integer.BYTES;

    buffer.position(valuesOffset);
    // Ensure the value buffer's limit equals to capacity.
    firstValueBuffer = buffer.slice();
    valueBuffers = new ByteBuffer[]{firstValueBuffer};
    buffer.position(indexOffset);
    headerBuffer = buffer.slice();
  }


  /**
   * Constructor for version two.
   */
  GenericIndexed(
      ByteBuffer[] valueBuffs,
      ByteBuffer headerBuff,
      ObjectStrategy<T> strategy,
      boolean allowReverseLookup,
      int logBaseTwoOfElementsPerValueFile,
      int numWritten
  )
  {
    this.versionOne = false;

    this.theBuffer = null;
    this.strategy = strategy;
    this.allowReverseLookup = allowReverseLookup;
    this.valueBuffers = valueBuffs;
    this.firstValueBuffer = valueBuffers[0];
    this.headerBuffer = headerBuff;
    this.size = numWritten;
    this.logBaseTwoOfElementsPerValueFile = logBaseTwoOfElementsPerValueFile;
    this.relativeIndexMask = (1 << logBaseTwoOfElementsPerValueFile) - 1;
    headerBuffer.order(ByteOrder.nativeOrder());
  }

  /**
   * Checks  if {@code index} a valid `element index` in GenericIndexed.
   * Similar to Preconditions.checkElementIndex() except this method throws {@link IAE} with custom error message.
   * <p>
   * Used here to get existing behavior(same error message and exception) of V1 GenericIndexed.
   *
   * @param index index identifying an element of an GenericIndexed.
   */
  private void checkIndex(int index)
  {
    if (index < 0) {
      throw new IAE("Index[%s] < 0", index);
    }
    if (index >= size) {
      throw new IAE("Index[%d] >= size[%d]", index, size);
    }
  }

  public Class<? extends T> getClazz()
  {
    return strategy.getClazz();
  }

  @Override
  public int size()
  {
    return size;
  }

  @Override
  public T get(int index)
  {
    return versionOne ? getVersionOne(index) : getVersionTwo(index);
  }

  /**
   * Returns the index of "value" in this GenericIndexed object, or (-(insertion point) - 1) if the value is not
   * present, in the manner of Arrays.binarySearch. This strengthens the contract of Indexed, which only guarantees
   * that values-not-found will return some negative number.
   *
   * @param value value to search for
   *
   * @return index of value, or negative number equal to (-(insertion point) - 1).
   */
  @Override
  public int indexOf(@Nullable T value)
  {
    if (!allowReverseLookup) {
      throw new UnsupportedOperationException("Reverse lookup not allowed.");
    }

    int minIndex = 0;
    int maxIndex = size - 1;
    while (minIndex <= maxIndex) {
      int currIndex = (minIndex + maxIndex) >>> 1;

      T currValue = get(currIndex);
      int comparison = strategy.compare(currValue, value);
      if (comparison == 0) {
        return currIndex;
      }

      if (comparison < 0) {
        minIndex = currIndex + 1;
      } else {
        maxIndex = currIndex - 1;
      }
    }

    return -(minIndex + 1);
  }

  @Override
  public boolean isSorted()
  {
    return allowReverseLookup;
  }

  @Override
  public Iterator<T> iterator()
  {
    return IndexedIterable.create(this).iterator();
  }

  @Override
  public long getSerializedSize()
  {
    if (!versionOne) {
      throw new UnsupportedOperationException("Method not supported for version 2 GenericIndexed.");
    }
    return getSerializedSizeVersionOne();
  }

  @Override
  public void writeTo(WritableByteChannel channel, FileSmoosher smoosher) throws IOException
  {
    if (versionOne) {
      writeToVersionOne(channel);
    } else {
      throw new UnsupportedOperationException(
          "GenericIndexed serialization for V2 is unsupported. Use GenericIndexedWriter instead.");
    }
  }

  /**
   * Create a non-thread-safe Indexed, which may perform better than the underlying Indexed.
   *
   * @return a non-thread-safe Indexed
   */
  public GenericIndexed<T>.BufferIndexed singleThreaded()
  {
    return versionOne ? singleThreadedVersionOne() : singleThreadedVersionTwo();
  }

  @Nullable
  private T copyBufferAndGet(ByteBuffer valueBuffer, int startOffset, int endOffset)
  {
    ByteBuffer copyValueBuffer = valueBuffer.asReadOnlyBuffer();
    int size = endOffset - startOffset;
    // When size is 0 and SQL compatibility is enabled also check for null marker before returning null.
    // When SQL compatibility is not enabled return null for both null as well as empty string case.
    if (size == 0 && (NullHandling.replaceWithDefault()
                      || copyValueBuffer.get(startOffset - Integer.BYTES) == NULL_VALUE_SIZE_MARKER)) {
      return null;
    }
    copyValueBuffer.position(startOffset);
    // fromByteBuffer must not modify the buffer limit
    return strategy.fromByteBuffer(copyValueBuffer, size);
  }

  @Override
  public void inspectRuntimeShape(RuntimeShapeInspector inspector)
  {
    inspector.visit("versionOne", versionOne);
    inspector.visit("headerBuffer", headerBuffer);
    if (versionOne) {
      inspector.visit("firstValueBuffer", firstValueBuffer);
    } else {
      // Inspecting just one example of valueBuffer, not needed to inspect the whole array, because all buffers in it
      // are the same.
      inspector.visit("valueBuffer", valueBuffers.length > 0 ? valueBuffers[0] : null);
    }
    inspector.visit("strategy", strategy);
  }

  /**
   * Single-threaded view.
   */
  public abstract class BufferIndexed implements Indexed<T>
  {
    int lastReadSize;

    @Override
    public int size()
    {
      return size;
    }

    @Override
    public T get(final int index)
    {
      final ByteBuffer buf = getByteBuffer(index);
      if (buf == null) {
        return null;
      }

      // Traditionally, ObjectStrategy.fromByteBuffer() is given a buffer with limit set to capacity, and the
      // actual limit is passed along as an extra parameter.
      final int len = buf.remaining();
      buf.limit(buf.capacity());
      return strategy.fromByteBuffer(buf, len);
    }

    @Nullable
    ByteBuffer bufferedIndexedGetByteBuffer(ByteBuffer copyValueBuffer, int startOffset, int endOffset)
    {
      int size = endOffset - startOffset;
      // When size is 0 and SQL compatibility is enabled also check for null marker before returning null.
      // When SQL compatibility is not enabled return null for both null as well as empty string case.
      if (size == 0 && (NullHandling.replaceWithDefault()
                        || copyValueBuffer.get(startOffset - Integer.BYTES) == NULL_VALUE_SIZE_MARKER)) {
        return null;
      }
      lastReadSize = size;

      // ObjectStrategy.fromByteBuffer() is allowed to reset the limit of the buffer. So if the limit is changed,
      // position() call could throw an exception, if the position is set beyond the new limit. Calling limit()
      // followed by position() is safe, because limit() resets position if needed.
      copyValueBuffer.limit(endOffset);
      copyValueBuffer.position(startOffset);
      return copyValueBuffer;
    }

    /**
     * Like {@link #get(int)}, but returns a {@link ByteBuffer} instead of using the {@link ObjectStrategy}.
     *
     * The returned ByteBuffer is reused by future calls. Callers must discard it before calling another method
     * on this BufferedIndexed object that may want to reuse the buffer.
     */
    @Nullable
    protected abstract ByteBuffer getByteBuffer(int index);

    /**
     * This method makes no guarantees with respect to thread safety
     *
     * @return the size in bytes of the last value read
     */
    int getLastValueSize()
    {
      return lastReadSize;
    }

    @Override
    public int indexOf(@Nullable T value)
    {
      if (!allowReverseLookup) {
        throw new UnsupportedOperationException("Reverse lookup not allowed.");
      }

      //noinspection ObjectEquality
      final boolean isByteBufferStrategy = strategy == UTF8_STRATEGY;

      int minIndex = 0;
      int maxIndex = size - 1;
      while (minIndex <= maxIndex) {
        int currIndex = (minIndex + maxIndex) >>> 1;

        int comparison;

        if (isByteBufferStrategy) {
          // Specialization avoids ByteBuffer allocation in strategy.fromByteBuffer.
          ByteBuffer currValue = getByteBuffer(currIndex);
          comparison = ByteBufferUtils.compareUtf8ByteBuffers(currValue, (ByteBuffer) value);
        } else {
          T currValue = get(currIndex);
          comparison = strategy.compare(currValue, value);
        }

        if (comparison == 0) {
          return currIndex;
        }

        if (comparison < 0) {
          minIndex = currIndex + 1;
        } else {
          maxIndex = currIndex - 1;
        }
      }

      return -(minIndex + 1);
    }

    @Override
    public boolean isSorted()
    {
      return allowReverseLookup;
    }

    @Override
    public Iterator<T> iterator()
    {
      return GenericIndexed.this.iterator();
    }
  }

  @Override
  public void close()
  {
    // nothing to close
  }

  ///////////////
  // VERSION ONE
  ///////////////

  private static <T> GenericIndexed<T> createGenericIndexedVersionOne(ByteBuffer byteBuffer, ObjectStrategy<T> strategy)
  {
    boolean allowReverseLookup = byteBuffer.get() == REVERSE_LOOKUP_ALLOWED;
    int size = byteBuffer.getInt();
    ByteBuffer bufferToUse = byteBuffer.asReadOnlyBuffer();
    bufferToUse.limit(bufferToUse.position() + size);
    byteBuffer.position(bufferToUse.limit());

    return new GenericIndexed<>(
        bufferToUse,
        strategy,
        allowReverseLookup
    );
  }

  private static <T, U> GenericIndexed<U> fromIterableVersionOne(
      Iterable<T> objectsIterable,
      ObjectStrategy<T> strategy,
      boolean allowReverseLookup,
      ObjectStrategy<U> resultObjectStrategy
  )
  {
    Iterator<T> objects = objectsIterable.iterator();
    if (!objects.hasNext()) {
      final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).putInt(0);
      buffer.flip();
      return new GenericIndexed<>(buffer, resultObjectStrategy, allowReverseLookup);
    }

    int count = 0;

    HeapByteBufferWriteOutBytes headerOut = new HeapByteBufferWriteOutBytes();
    HeapByteBufferWriteOutBytes valuesOut = new HeapByteBufferWriteOutBytes();
    try {
      T prevVal = null;
      do {
        count++;
        T next = objects.next();
        if (allowReverseLookup && prevVal != null && !(strategy.compare(prevVal, next) < 0)) {
          allowReverseLookup = false;
        }

        if (next != null) {
          valuesOut.writeInt(0);
          strategy.writeTo(next, valuesOut);
        } else {
          valuesOut.writeInt(NULL_VALUE_SIZE_MARKER);
        }

        headerOut.writeInt(Ints.checkedCast(valuesOut.size()));

        if (prevVal instanceof Closeable) {
          CloseableUtils.closeAndWrapExceptions((Closeable) prevVal);
        }
        prevVal = next;
      } while (objects.hasNext());

      if (prevVal instanceof Closeable) {
        CloseableUtils.closeAndWrapExceptions((Closeable) prevVal);
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }

    ByteBuffer theBuffer = ByteBuffer.allocate(Ints.checkedCast(Integer.BYTES + headerOut.size() + valuesOut.size()));
    theBuffer.putInt(count);
    headerOut.writeTo(theBuffer);
    valuesOut.writeTo(theBuffer);
    theBuffer.flip();

    return new GenericIndexed<>(theBuffer.asReadOnlyBuffer(), resultObjectStrategy, allowReverseLookup);
  }

  private long getSerializedSizeVersionOne()
  {
    return META_SERDE_HELPER.size(this) + (long) theBuffer.remaining();
  }

  @Nullable
  private T getVersionOne(int index)
  {
    checkIndex(index);

    final int startOffset;
    final int endOffset;

    if (index == 0) {
      startOffset = Integer.BYTES;
      endOffset = headerBuffer.getInt(0);
    } else {
      int headerPosition = (index - 1) * Integer.BYTES;
      startOffset = headerBuffer.getInt(headerPosition) + Integer.BYTES;
      endOffset = headerBuffer.getInt(headerPosition + Integer.BYTES);
    }
    return copyBufferAndGet(firstValueBuffer, startOffset, endOffset);
  }

  private BufferIndexed singleThreadedVersionOne()
  {
    final ByteBuffer copyBuffer = firstValueBuffer.asReadOnlyBuffer();
    return new BufferIndexed()
    {
      @Nullable
      @Override
      protected ByteBuffer getByteBuffer(final int index)
      {
        checkIndex(index);

        final int startOffset;
        final int endOffset;

        if (index == 0) {
          startOffset = Integer.BYTES;
          endOffset = headerBuffer.getInt(0);
        } else {
          int headerPosition = (index - 1) * Integer.BYTES;
          startOffset = headerBuffer.getInt(headerPosition) + Integer.BYTES;
          endOffset = headerBuffer.getInt(headerPosition + Integer.BYTES);
        }
        return bufferedIndexedGetByteBuffer(copyBuffer, startOffset, endOffset);
      }

      @Override
      public void inspectRuntimeShape(RuntimeShapeInspector inspector)
      {
        inspector.visit("headerBuffer", headerBuffer);
        inspector.visit("copyBuffer", copyBuffer);
        inspector.visit("strategy", strategy);
      }
    };
  }

  private void writeToVersionOne(WritableByteChannel channel) throws IOException
  {
    META_SERDE_HELPER.writeTo(channel, this);
    Channels.writeFully(channel, theBuffer.asReadOnlyBuffer());
  }


  ///////////////
  // VERSION TWO
  ///////////////

  private static <T> GenericIndexed<T> createGenericIndexedVersionTwo(
      ByteBuffer byteBuffer,
      ObjectStrategy<T> strategy,
      SmooshedFileMapper fileMapper
  )
  {
    if (fileMapper == null) {
      throw new IAE("SmooshedFileMapper can not be null for version 2.");
    }
    boolean allowReverseLookup = byteBuffer.get() == REVERSE_LOOKUP_ALLOWED;
    int logBaseTwoOfElementsPerValueFile = byteBuffer.getInt();
    int numElements = byteBuffer.getInt();

    try {
      String columnName = SERIALIZER_UTILS.readString(byteBuffer);
      int elementsPerValueFile = 1 << logBaseTwoOfElementsPerValueFile;
      int numberOfFilesRequired = getNumberOfFilesRequired(elementsPerValueFile, numElements);
      ByteBuffer[] valueBuffersToUse = new ByteBuffer[numberOfFilesRequired];
      for (int i = 0; i < numberOfFilesRequired; i++) {
        // SmooshedFileMapper.mapFile() contract guarantees that the valueBuffer's limit equals to capacity.
        ByteBuffer valueBuffer = fileMapper.mapFile(GenericIndexedWriter.generateValueFileName(columnName, i));
        valueBuffersToUse[i] = valueBuffer.asReadOnlyBuffer();
      }
      ByteBuffer headerBuffer = fileMapper.mapFile(GenericIndexedWriter.generateHeaderFileName(columnName));
      return new GenericIndexed<>(
          valueBuffersToUse,
          headerBuffer,
          strategy,
          allowReverseLookup,
          logBaseTwoOfElementsPerValueFile,
          numElements
      );
    }
    catch (IOException e) {
      throw new RuntimeException("File mapping failed.", e);
    }
  }

  @Nullable
  private T getVersionTwo(int index)
  {
    checkIndex(index);

    final int startOffset;
    final int endOffset;

    int relativePositionOfIndex = index & relativeIndexMask;
    if (relativePositionOfIndex == 0) {
      int headerPosition = index * Integer.BYTES;
      startOffset = Integer.BYTES;
      endOffset = headerBuffer.getInt(headerPosition);
    } else {
      int headerPosition = (index - 1) * Integer.BYTES;
      startOffset = headerBuffer.getInt(headerPosition) + Integer.BYTES;
      endOffset = headerBuffer.getInt(headerPosition + Integer.BYTES);
    }
    int fileNum = index >> logBaseTwoOfElementsPerValueFile;
    return copyBufferAndGet(valueBuffers[fileNum], startOffset, endOffset);
  }

  private BufferIndexed singleThreadedVersionTwo()
  {
    final ByteBuffer[] copyValueBuffers = new ByteBuffer[valueBuffers.length];
    for (int i = 0; i < valueBuffers.length; i++) {
      copyValueBuffers[i] = valueBuffers[i].asReadOnlyBuffer();
    }

    return new BufferIndexed()
    {
      @Nullable
      @Override
      protected ByteBuffer getByteBuffer(int index)
      {
        checkIndex(index);

        final int startOffset;
        final int endOffset;

        int relativePositionOfIndex = index & relativeIndexMask;
        if (relativePositionOfIndex == 0) {
          int headerPosition = index * Integer.BYTES;
          startOffset = 4;
          endOffset = headerBuffer.getInt(headerPosition);
        } else {
          int headerPosition = (index - 1) * Integer.BYTES;
          startOffset = headerBuffer.getInt(headerPosition) + Integer.BYTES;
          endOffset = headerBuffer.getInt(headerPosition + Integer.BYTES);
        }
        int fileNum = index >> logBaseTwoOfElementsPerValueFile;
        return bufferedIndexedGetByteBuffer(copyValueBuffers[fileNum], startOffset, endOffset);
      }

      @Override
      public void inspectRuntimeShape(RuntimeShapeInspector inspector)
      {
        inspector.visit("headerBuffer", headerBuffer);
        // Inspecting just one example of copyValueBuffer, not needed to inspect the whole array, because all buffers
        // in it are the same.
        inspector.visit("copyValueBuffer", copyValueBuffers.length > 0 ? copyValueBuffers[0] : null);
        inspector.visit("strategy", strategy);
      }
    };
  }
}
