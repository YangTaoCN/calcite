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
package org.apache.calcite.rel;

import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of a tree of {@link RelNode}.
 */
public class RelRoot {
  public final RelNode rel;
  public final RelDataType validatedRowType;
  public final SqlKind kind;
  public final ImmutableList<Pair<Integer, String>> fields;
  public final RelCollation collation;

  /**
   * Creates a RelRoot.
   *
   * @param validatedRowType Original row type returned by query validator
   * @param kind Type of query (SELECT, UPDATE, ...)
   */

  public RelRoot(RelNode rel, RelDataType validatedRowType, SqlKind kind,
       List<Pair<Integer, String>> fields, RelCollation collation) {
    this.rel = rel;
    this.validatedRowType = validatedRowType;
    this.kind = kind;
    this.fields = ImmutableList.copyOf(fields);
    this.collation = Preconditions.checkNotNull(collation);
  }

  /** Creates a copy of this RelRoot, assigning a {@link RelNode}. */
  public RelRoot copy(RelNode rel) {
    if (rel == this.rel) {
      return this;
    }
    return new RelRoot(rel, validatedRowType, kind, fields, collation);
  }

  /** Creates a copy, assigning a new kind. */
  public RelRoot withKind(SqlKind kind) {
    if (kind == this.kind) {
      return this;
    }
    return new RelRoot(rel, validatedRowType, kind, fields, collation);
  }

  /** Returns the root relational expression, creating a {@link LogicalProject}
   * if necessary to remove fields that are not needed. */
  public RelNode project() {
    final List<Integer> list =
        ImmutableIntList.identity(rel.getRowType().getFieldCount());
    if (!list.equals(Pair.left(fields)) && !SqlKind.DML.contains(kind)) {
      final List<RexNode> projects = new ArrayList<>();
      final RexBuilder rexBuilder = rel.getCluster().getRexBuilder();
      for (Pair<Integer, String> field : fields) {
        projects.add(rexBuilder.makeInputRef(rel, field.left));
      }
      return LogicalProject.create(rel, projects, Pair.right(fields));
    }
    return rel;
  }
}

// End RelRoot.java
