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
import org.apache.calcite.rel.logical.LogicalMinus;

/**
 * Rule to convert an {@link org.apache.calcite.rel.logical.LogicalMinus} to an
 * {@link EnumerableMinus}.
 */
class EnumerableMinusRule extends ConverterRule {
  EnumerableMinusRule() {
    super(LogicalMinus.class, Convention.NONE, EnumerableConvention.INSTANCE,
        "EnumerableMinusRule");
  }

  public RelNode convert(RelOptRuleCall call, RelNode rel) {
    final LogicalMinus minus = (LogicalMinus) rel;
    if (minus.all) {
      return null; // EXCEPT ALL not implemented
    }
    final EnumerableConvention out = EnumerableConvention.INSTANCE;
    final RelTraitSet traitSet =
        rel.getTraitSet().replace(
            EnumerableConvention.INSTANCE);
    return new EnumerableMinus(rel.getCluster(), traitSet,
        call.convertList(minus.getInputs(), out), false);
  }
}

// End EnumerableMinusRule.java
