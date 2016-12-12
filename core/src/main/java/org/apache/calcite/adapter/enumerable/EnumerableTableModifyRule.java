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
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.schema.ModifiableTable;

/** Planner rule that converts a
 * {@link org.apache.calcite.rel.logical.LogicalTableModify}
 * relational expression
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
public class EnumerableTableModifyRule extends ConverterRule {
  EnumerableTableModifyRule() {
    super(LogicalTableModify.class, Convention.NONE,
        EnumerableConvention.INSTANCE, "EnumerableTableModificationRule");
  }

  @Override public RelNode convert(RelOptRuleCall call, RelNode rel) {
    final LogicalTableModify modify =
        (LogicalTableModify) rel;
    final ModifiableTable modifiableTable =
        modify.getTable().unwrap(ModifiableTable.class);
    if (modifiableTable == null) {
      return null;
    }
    final RelTraitSet traitSet =
        modify.getTraitSet().replace(EnumerableConvention.INSTANCE);
    return new EnumerableTableModify(
        modify.getCluster(), traitSet,
        modify.getTable(),
        modify.getCatalogReader(),
        call.convert(modify.getInput(), traitSet),
        modify.getOperation(),
        modify.getUpdateColumnList(),
        modify.getSourceExpressionList(),
        modify.isFlattened());
  }
}

// End EnumerableTableModifyRule.java
