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

package org.apache.druid.query.aggregation.datasketches.quantiles.sql;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.datasketches.quantiles.DoublesSketchAggregatorFactory;
import org.apache.druid.query.aggregation.datasketches.quantiles.DoublesSketchToQuantilePostAggregator;
import org.apache.druid.query.aggregation.post.FieldAccessPostAggregator;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.aggregation.Aggregation;
import org.apache.druid.sql.calcite.aggregation.Aggregations;
import org.apache.druid.sql.calcite.aggregation.SqlAggregator;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.Expressions;
import org.apache.druid.sql.calcite.expression.OperatorConversions;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.apache.druid.sql.calcite.rel.VirtualColumnRegistry;

import javax.annotation.Nullable;
import java.util.List;

public class DoublesSketchApproxQuantileSqlAggregator implements SqlAggregator
{
  public static final String CTX_APPROX_QUANTILE_DS_MAX_STREAM_LENGTH = "approxQuantileDsMaxStreamLength";

  private static final String NAME = "APPROX_QUANTILE_DS";
  private static final SqlAggFunction FUNCTION_INSTANCE =
      OperatorConversions.aggregatorBuilder(NAME)
          .operandNames("column", "probability", "k")
          .operandTypes(SqlTypeFamily.ANY, SqlTypeFamily.NUMERIC, SqlTypeFamily.EXACT_NUMERIC)
          .returnTypeNonNull(SqlTypeName.DOUBLE)
          .requiredOperandCount(2)
          .literalOperands(1, 2)
          .functionCategory(SqlFunctionCategory.NUMERIC)
          .build();

  @Override
  public SqlAggFunction calciteFunction()
  {
    return FUNCTION_INSTANCE;
  }

  @Nullable
  @Override
  public Aggregation toDruidAggregation(
      final PlannerContext plannerContext,
      final RowSignature rowSignature,
      final VirtualColumnRegistry virtualColumnRegistry,
      final RexBuilder rexBuilder,
      final String name,
      final AggregateCall aggregateCall,
      final Project project,
      final List<Aggregation> existingAggregations,
      final boolean finalizeAggregations
  )
  {
    final DruidExpression input = Aggregations.toDruidExpressionForNumericAggregator(
        plannerContext,
        rowSignature,
        Expressions.fromFieldAccess(
            rexBuilder.getTypeFactory(),
            rowSignature,
            project,
            aggregateCall.getArgList().get(0)
        )
    );
    if (input == null) {
      return null;
    }

    final AggregatorFactory aggregatorFactory;
    final String histogramName = StringUtils.format("%s:agg", name);
    final RexNode probabilityArg = Expressions.fromFieldAccess(
        rexBuilder.getTypeFactory(),
        rowSignature,
        project,
        aggregateCall.getArgList().get(1)
    );

    if (!probabilityArg.isA(SqlKind.LITERAL)) {
      // Probability must be a literal in order to plan.
      return null;
    }

    final float probability = ((Number) RexLiteral.value(probabilityArg)).floatValue();
    final int k;

    if (aggregateCall.getArgList().size() >= 3) {
      final RexNode resolutionArg = Expressions.fromFieldAccess(
          rexBuilder.getTypeFactory(),
          rowSignature,
          project,
          aggregateCall.getArgList().get(2)
      );

      if (!resolutionArg.isA(SqlKind.LITERAL)) {
        // Resolution must be a literal in order to plan.
        return null;
      }

      k = ((Number) RexLiteral.value(resolutionArg)).intValue();
    } else {
      k = DoublesSketchAggregatorFactory.DEFAULT_K;
    }

    // Look for existing matching aggregatorFactory.
    for (final Aggregation existing : existingAggregations) {
      for (AggregatorFactory factory : existing.getAggregatorFactories()) {
        if (factory instanceof DoublesSketchAggregatorFactory) {
          final DoublesSketchAggregatorFactory theFactory = (DoublesSketchAggregatorFactory) factory;

          // Check input for equivalence.
          final boolean inputMatches;
          final DruidExpression virtualInput =
              virtualColumnRegistry.findVirtualColumnExpressions(theFactory.requiredFields())
                                   .stream()
                                   .findFirst()
                                   .orElse(null);

          if (virtualInput == null) {
            inputMatches = input.isDirectColumnAccess() && input.getDirectColumn().equals(theFactory.getFieldName());
          } else {
            inputMatches = virtualInput.equals(input);
          }

          final boolean matches = inputMatches
                                  && theFactory.getK() == k;

          if (matches) {
            // Found existing one. Use this.
            return Aggregation.create(
                ImmutableList.of(),
                new DoublesSketchToQuantilePostAggregator(
                    name,
                    new FieldAccessPostAggregator(
                        factory.getName(),
                        factory.getName()
                    ),
                    probability
                )
            );
          }
        }
      }
    }

    // No existing match found. Create a new one.
    if (input.isDirectColumnAccess()) {
      aggregatorFactory = new DoublesSketchAggregatorFactory(
          histogramName,
          input.getDirectColumn(),
          k,
          getMaxStreamLengthFromQueryContext(plannerContext.queryContext()),
          true
      );
    } else {
      String virtualColumnName = virtualColumnRegistry.getOrCreateVirtualColumnForExpression(
          input,
          ColumnType.FLOAT
      );
      aggregatorFactory = new DoublesSketchAggregatorFactory(
          histogramName,
          virtualColumnName,
          k,
          getMaxStreamLengthFromQueryContext(plannerContext.queryContext()),
          true
      );
    }

    return Aggregation.create(
        ImmutableList.of(aggregatorFactory),
        new DoublesSketchToQuantilePostAggregator(
            name,
            new FieldAccessPostAggregator(
                histogramName,
                histogramName
            ),
            probability
        )
    );
  }

  static long getMaxStreamLengthFromQueryContext(QueryContext queryContext)
  {
    return queryContext.getLong(
        CTX_APPROX_QUANTILE_DS_MAX_STREAM_LENGTH,
        DoublesSketchAggregatorFactory.DEFAULT_MAX_STREAM_LENGTH
    );
  }
}
