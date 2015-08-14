/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlCountAggFunction;
import org.apache.calcite.sql.fun.SqlMinMaxAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlSumAggFunction;
import org.apache.calcite.sql.fun.SqlSumEmptyIsZeroAggFunction;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner rule that pushes an
 * {@link org.apache.calcite.rel.core.Aggregate}
 * past a non-distinct {@link org.apache.calcite.rel.core.Union}.
 */
public class AggregateUnionTransposeRule extends RelOptRule {
  public static final AggregateUnionTransposeRule INSTANCE =
      new AggregateUnionTransposeRule(LogicalAggregate.class,
          RelFactories.DEFAULT_AGGREGATE_FACTORY,
          LogicalUnion.class,
          RelFactories.DEFAULT_SET_OP_FACTORY);

  private final RelFactories.AggregateFactory aggregateFactory;

  private final RelFactories.SetOpFactory setOpFactory;

  private static final Map<Class<? extends SqlAggFunction>, Boolean>
  SUPPORTED_AGGREGATES = new IdentityHashMap<>();

  static {
    SUPPORTED_AGGREGATES.put(SqlMinMaxAggFunction.class, true);
    SUPPORTED_AGGREGATES.put(SqlCountAggFunction.class, true);
    SUPPORTED_AGGREGATES.put(SqlSumAggFunction.class, true);
    SUPPORTED_AGGREGATES.put(SqlSumEmptyIsZeroAggFunction.class, true);
  }

  /**
   * Constructor.
   */
  public AggregateUnionTransposeRule(Class<? extends Aggregate> aggregateClass,
      RelFactories.AggregateFactory aggregateFactory,
      Class<? extends Union> unionClass,
      RelFactories.SetOpFactory setOpFactory) {
    super(
        operand(aggregateClass,
            operand(unionClass, any())));
    this.aggregateFactory = aggregateFactory;
    this.setOpFactory = setOpFactory;
  }

  public void onMatch(RelOptRuleCall call) {
    Aggregate aggRel = call.rel(0);
    Union union = call.rel(1);

    if (!union.all) {
      // This transformation is only valid for UNION ALL.
      // Consider t1(i) with rows (5), (5) and t2(i) with
      // rows (5), (10), and the query
      // select sum(i) from (select i from t1) union (select i from t2).
      // The correct answer is 15.  If we apply the transformation,
      // we get
      // select sum(i) from
      // (select sum(i) as i from t1) union (select sum(i) as i from t2)
      // which yields 25 (incorrect).
      return;
    }

    int groupCount = aggRel.getGroupSet().cardinality();

    List<AggregateCall> transformedAggCalls =
        transformAggCalls(
            aggRel.copy(aggRel.getTraitSet(), aggRel.getInput(), false,
                aggRel.getGroupSet(), null, aggRel.getAggCallList()),
            groupCount, aggRel.getAggCallList());
    if (transformedAggCalls == null) {
      // we've detected the presence of something like AVG,
      // which we can't handle
      return;
    }

    boolean anyTransformed = false;

    // create corresponding aggregates on top of each union child
    final List<RelNode> newUnionInputs = new ArrayList<>();
    for (RelNode input : union.getInputs()) {
      boolean alreadyUnique =
          RelMdUtil.areColumnsDefinitelyUnique(
              input,
              aggRel.getGroupSet());

      if (alreadyUnique) {
        newUnionInputs.add(input);
      } else {
        anyTransformed = true;
        newUnionInputs.add(
            aggregateFactory.createAggregate(input, false,
                aggRel.getGroupSet(), null, aggRel.getAggCallList()));
      }
    }

    if (!anyTransformed) {
      // none of the children could benefit from the push-down,
      // so bail out (preventing the infinite loop to which most
      // planners would succumb)
      return;
    }

    // create a new union whose children are the aggregates created above
    RelNode newUnion = setOpFactory.createSetOp(SqlKind.UNION,
        newUnionInputs, true);

    RelNode newTopAggRel =
        aggregateFactory.createAggregate(newUnion, aggRel.indicator,
            aggRel.getGroupSet(), aggRel.getGroupSets(), transformedAggCalls);

    call.transformTo(newTopAggRel);
  }

  private List<AggregateCall> transformAggCalls(RelNode input, int groupCount,
      List<AggregateCall> origCalls) {
    final List<AggregateCall> newCalls = Lists.newArrayList();
    for (Ord<AggregateCall> ord : Ord.zip(origCalls)) {
      final AggregateCall origCall = ord.e;
      if (origCall.isDistinct()
          || !SUPPORTED_AGGREGATES.containsKey(origCall.getAggregation()
              .getClass())) {
        return null;
      }
      final SqlAggFunction aggFun;
      final RelDataType aggType;
      if (origCall.getAggregation() == SqlStdOperatorTable.COUNT) {
        aggFun = SqlStdOperatorTable.SUM0;
        // count(any) is always not null, however nullability of sum might
        // depend on the number of columns in GROUP BY.
        // Here we use SUM0 since we are sure we will not face nullable
        // inputs nor we'll face empty set.
        aggType = null;
      } else {
        aggFun = origCall.getAggregation();
        aggType = origCall.getType();
      }
      AggregateCall newCall =
          AggregateCall.create(aggFun, origCall.isDistinct(),
              ImmutableList.of(groupCount + ord.i), -1, groupCount, input,
              aggType, origCall.getName());
      newCalls.add(newCall);
    }
    return newCalls;
  }
}

// End AggregateUnionTransposeRule.java
