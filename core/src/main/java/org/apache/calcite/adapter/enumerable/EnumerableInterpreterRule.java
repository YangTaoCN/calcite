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

import org.apache.calcite.interpreter.BindableConvention;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

/**
 * Planner rule that converts {@link org.apache.calcite.interpreter.BindableRel}
 * to {@link org.apache.calcite.adapter.enumerable.EnumerableRel} by creating
 * an {@link org.apache.calcite.adapter.enumerable.EnumerableInterpreter}.
 */
public class EnumerableInterpreterRule extends ConverterRule {
  public static final EnumerableInterpreterRule INSTANCE =
      new EnumerableInterpreterRule();

  private EnumerableInterpreterRule() {
    super(RelNode.class, BindableConvention.INSTANCE,
        EnumerableConvention.INSTANCE, "EnumerableInterpreterRule");
  }

  //~ Methods ----------------------------------------------------------------

  @Override public RelNode convert(RelOptRuleCall call, RelNode rel) {
    return EnumerableInterpreter.create(rel, 0.5d);
  }
}

// End EnumerableInterpreterRule.java
