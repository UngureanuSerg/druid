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

package org.apache.druid.server.coordinator.balancer;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.druid.client.ImmutableDruidDataSource;
import org.apache.druid.client.ImmutableDruidServer;
import org.apache.druid.client.ImmutableDruidServerTests;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.server.coordinator.ServerHolder;
import org.apache.druid.server.coordinator.loading.LoadQueuePeonTester;
import org.apache.druid.timeline.DataSegment;
import org.easymock.EasyMock;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DiskNormalizedCostBalancerStrategyTest
{
  private static final Interval DAY = Intervals.of("2015-01-01T00/2015-01-01T01");

  /**
   * Create Druid cluster with serverCount servers having maxSegments segments each, and 1 server with 98 segment
   * Cost Balancer Strategy should assign the next segment to the server with less segments.
   */
  public static List<ServerHolder> setupDummyCluster(int serverCount, int maxSegments)
  {
    List<ServerHolder> serverHolderList = new ArrayList<>();
    // Create 10 servers with current size being 3K & max size being 10K
    // Each having having 100 segments
    for (int i = 0; i < serverCount; i++) {
      LoadQueuePeonTester fromPeon = new LoadQueuePeonTester();

      List<DataSegment> segments = IntStream
          .range(0, maxSegments)
          .mapToObj(j -> getSegment(j))
          .collect(Collectors.toList());
      ImmutableDruidDataSource dataSource = new ImmutableDruidDataSource("DUMMY", Collections.emptyMap(), segments);

      serverHolderList.add(
          new ServerHolder(
              new ImmutableDruidServer(
                  new DruidServerMetadata("DruidServer_Name_" + i, "localhost", null, 10000000L, ServerType.HISTORICAL, "hot", 1),
                  3000L,
                  ImmutableMap.of("DUMMY", dataSource),
                  segments.size()
              ),
              fromPeon
          ));
    }

    // The best server to be available for next segment assignment has greater max Size
    LoadQueuePeonTester fromPeon = new LoadQueuePeonTester();
    ImmutableDruidServer druidServer = EasyMock.createMock(ImmutableDruidServer.class);
    EasyMock.expect(druidServer.getName()).andReturn("BEST_SERVER").anyTimes();
    EasyMock.expect(druidServer.getCurrSize()).andReturn(3000L).anyTimes();
    EasyMock.expect(druidServer.getMaxSize()).andReturn(100000000L).anyTimes();

    EasyMock.expect(druidServer.getSegment(EasyMock.anyObject())).andReturn(null).anyTimes();
    List<DataSegment> segments = new ArrayList<>();
    for (int j = 0; j < maxSegments; j++) {
      DataSegment segment = getSegment(j);
      segments.add(segment);
      EasyMock.expect(druidServer.getSegment(segment.getId())).andReturn(segment).anyTimes();
    }
    ImmutableDruidServerTests.expectSegments(druidServer, segments);

    EasyMock.replay(druidServer);
    serverHolderList.add(new ServerHolder(druidServer, fromPeon));
    return serverHolderList;
  }

  /**
   * Returns segment with dummy id and size 100
   *
   * @param index
   *
   * @return segment
   */
  public static DataSegment getSegment(int index)
  {
    return getSegment(index, "DUMMY", DAY);
  }

  public static DataSegment getSegment(int index, String dataSource, Interval interval)
  {
    // Not using EasyMock as it hampers the performance of multithreads.
    DataSegment segment = new DataSegment(
        dataSource,
        interval,
        String.valueOf(index),
        new ConcurrentHashMap<>(),
        new ArrayList<>(),
        new ArrayList<>(),
        null,
        0,
        index * 100L
    );
    return segment;
  }

  @Test
  public void testNormalizedCostBalancerMultiThreadedStrategy()
  {
    List<ServerHolder> serverHolderList = setupDummyCluster(10, 20);
    DataSegment segment = getSegment(1000);

    BalancerStrategy strategy = new DiskNormalizedCostBalancerStrategy(
        MoreExecutors.listeningDecorator(Execs.multiThreaded(4, "DiskNormalizedCostBalancerStrategyTest-%d"))
    );
    ServerHolder holder = strategy.findServersToLoadSegment(segment, serverHolderList).next();
    Assert.assertNotNull("Should be able to find a place for new segment!!", holder);
    Assert.assertEquals("Best Server should be BEST_SERVER", "BEST_SERVER", holder.getServer().getName());
  }

  @Test
  public void testNormalizedCostBalancerSingleThreadStrategy()
  {
    List<ServerHolder> serverHolderList = setupDummyCluster(10, 20);
    DataSegment segment = getSegment(1000);

    BalancerStrategy strategy = new DiskNormalizedCostBalancerStrategy(
        MoreExecutors.listeningDecorator(Execs.multiThreaded(1, "DiskNormalizedCostBalancerStrategyTest-%d"))
    );
    ServerHolder holder = strategy.findServersToLoadSegment(segment, serverHolderList).next();
    Assert.assertNotNull("Should be able to find a place for new segment!!", holder);
    Assert.assertEquals("Best Server should be BEST_SERVER", "BEST_SERVER", holder.getServer().getName());
  }
}
