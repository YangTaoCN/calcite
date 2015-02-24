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
package org.apache.calcite.rel.metadata;

import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Exchange;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableNullableList;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Default implementations of the
 * {@link org.apache.calcite.rel.metadata.BuiltInMetadata.Size}
 * metadata provider for the standard logical algebra.
 *
 * @see RelMetadataQuery#getAverageRowSize
 * @see RelMetadataQuery#getAverageColumnSizes
 * @see RelMetadataQuery#getAverageColumnSizesNotNull
 */
public class RelMdSize {
  /** Source for
   * {@link org.apache.calcite.rel.metadata.BuiltInMetadata.Size}. */
  public static final RelMetadataProvider SOURCE =
      ReflectiveRelMetadataProvider.reflectiveSource(new RelMdSize(),
          BuiltInMethod.AVERAGE_COLUMN_SIZES.method,
          BuiltInMethod.AVERAGE_ROW_SIZE.method);

  //~ Constructors -----------------------------------------------------------

  private RelMdSize() {}

  //~ Methods ----------------------------------------------------------------

  /** Catch-all implementation for
   * {@link org.apache.calcite.rel.metadata.BuiltInMetadata.Size#averageRowSize()},
   * invoked using reflection.
   *
   * @see org.apache.calcite.rel.metadata.RelMetadataQuery#getAverageRowSize
   */
  public Double averageRowSize(RelNode rel) {
    final List<Double> averageColumnSizes =
        RelMetadataQuery.getAverageColumnSizes(rel);
    if (averageColumnSizes == null) {
      return null;
    }
    Double d = 0d;
    final List<RelDataTypeField> fields = rel.getRowType().getFieldList();
    for (Pair<Double, RelDataTypeField> p
        : Pair.zip(averageColumnSizes, fields)) {
      if (p.left == null) {
        d += averageFieldValueSize(p.right);
      } else {
        d += p.left;
      }
    }
    return d;
  }

  /** Catch-all implementation for
   * {@link org.apache.calcite.rel.metadata.BuiltInMetadata.Size#averageColumnSizes()},
   * invoked using reflection.
   *
   * @see org.apache.calcite.rel.metadata.RelMetadataQuery#getAverageColumnSizes
   */
  public List<Double> averageColumnSizes(RelNode rel) {
    return null; // absolutely no idea
  }

  public List<Double> averageColumnSizes(Filter rel) {
    return RelMetadataQuery.getAverageColumnSizes(rel.getInput());
  }

  public List<Double> averageColumnSizes(Sort rel) {
    return RelMetadataQuery.getAverageColumnSizes(rel.getInput());
  }

  public List<Double> averageColumnSizes(Exchange rel) {
    return RelMetadataQuery.getAverageColumnSizes(rel.getInput());
  }

  public List<Double> averageColumnSizes(Project rel) {
    final List<Double> inputColumnSizes =
        RelMetadataQuery.getAverageColumnSizesNotNull(rel.getInput());
    final ImmutableNullableList.Builder<Double> sizes =
        ImmutableNullableList.builder();
    for (RexNode project : rel.getProjects()) {
      sizes.add(averageRexSize(project, inputColumnSizes));
    }
    return sizes.build();
  }

  public List<Double> averageColumnSizes(Values rel) {
    final List<RelDataTypeField> fields = rel.getRowType().getFieldList();
    final ImmutableList.Builder<Double> list = ImmutableList.builder();
    for (int i = 0; i < fields.size(); i++) {
      RelDataTypeField field = fields.get(i);
      double d;
      if (rel.getTuples().isEmpty()) {
        d = averageTypeValueSize(field.getType());
      } else {
        d = 0;
        for (ImmutableList<RexLiteral> literals : rel.getTuples()) {
          d += typeValueSize(field.getType(), literals.get(i).getValue());
        }
        d /= rel.getTuples().size();
      }
      list.add(d);
    }
    return list.build();
  }

  public List<Double> averageColumnSizes(TableScan rel) {
    final List<RelDataTypeField> fields = rel.getRowType().getFieldList();
    final ImmutableList.Builder<Double> list = ImmutableList.builder();
    for (RelDataTypeField field : fields) {
      list.add(averageTypeValueSize(field.getType()));
    }
    return list.build();
  }

  public List<Double> averageColumnSizes(Aggregate rel) {
    final List<Double> inputColumnSizes =
        RelMetadataQuery.getAverageColumnSizesNotNull(rel.getInput());
    final ImmutableList.Builder<Double> list = ImmutableList.builder();
    for (int key : rel.getGroupSet()) {
      list.add(inputColumnSizes.get(key));
    }
    for (AggregateCall aggregateCall : rel.getAggCallList()) {
      list.add(averageTypeValueSize(aggregateCall.type));
    }
    return list.build();
  }

  public List<Double> averageColumnSizes(Join rel) {
    final RelNode left = rel.getLeft();
    final RelNode right = rel.getRight();
    final List<Double> lefts =
        RelMetadataQuery.getAverageColumnSizes(left);
    final List<Double> rights =
        RelMetadataQuery.getAverageColumnSizes(right);
    if (lefts == null && rights == null) {
      return null;
    }
    final int fieldCount = rel.getRowType().getFieldCount();
    Double[] sizes = new Double[fieldCount];
    if (lefts != null) {
      lefts.toArray(sizes);
    }
    if (rights != null) {
      final int leftCount = left.getRowType().getFieldCount();
      for (int i = 0; i < rights.size(); i++) {
        sizes[leftCount + i] = rights.get(i);
      }
    }
    return ImmutableNullableList.copyOf(sizes);
  }

  public List<Double> averageColumnSizes(Intersect rel) {
    return RelMetadataQuery.getAverageColumnSizes(rel.getInput(0));
  }

  public List<Double> averageColumnSizes(Minus rel) {
    return RelMetadataQuery.getAverageColumnSizes(rel.getInput(0));
  }

  public List<Double> averageColumnSizes(Union rel) {
    final int fieldCount = rel.getRowType().getFieldCount();
    List<List<Double>> inputColumnSizeList = Lists.newArrayList();
    for (RelNode input : rel.getInputs()) {
      final List<Double> inputSizes =
          RelMetadataQuery.getAverageColumnSizes(input);
      if (inputSizes != null) {
        inputColumnSizeList.add(inputSizes);
      }
    }
    switch (inputColumnSizeList.size()) {
    case 0:
      return null; // all were null
    case 1:
      return inputColumnSizeList.get(0); // all but one were null
    }
    final ImmutableNullableList.Builder<Double> sizes =
        ImmutableNullableList.builder();
    int nn = 0;
    for (int i = 0; i < fieldCount; i++) {
      double d = 0d;
      int n = 0;
      for (List<Double> inputColumnSizes : inputColumnSizeList) {
        Double d2 = inputColumnSizes.get(i);
        if (d2 != null) {
          d += d2;
          ++n;
          ++nn;
        }
      }
      sizes.add(n > 0 ? d / n : null);
    }
    if (nn == 0) {
      return null; // all columns are null
    }
    return sizes.build();
  }

  /** Estimates the average size (in bytes) of a value of a field, knowing
   * nothing more than its type.
   *
   * <p>We assume that the proportion of nulls is negligible, even if the field
   * is nullable.
   */
  protected Double averageFieldValueSize(RelDataTypeField field) {
    return averageTypeValueSize(field.getType());
  }

  /** Estimates the average size (in bytes) of a value of a type.
   *
   * <p>We assume that the proportion of nulls is negligible, even if the type
   * is nullable.
   */
  public Double averageTypeValueSize(RelDataType type) {
    switch (type.getSqlTypeName()) {
    case BOOLEAN:
    case TINYINT:
      return 1d;
    case SMALLINT:
      return 2d;
    case INTEGER:
    case FLOAT:
    case REAL:
    case DATE:
    case TIME:
      return 4d;
    case BIGINT:
    case DOUBLE:
    case TIMESTAMP:
    case INTERVAL_DAY_TIME:
    case INTERVAL_YEAR_MONTH:
      return 8d;
    case BINARY:
      return (double) type.getPrecision();
    case VARBINARY:
      return Math.min((double) type.getPrecision(), 100d);
    case CHAR:
      return (double) type.getPrecision() * Character.BYTES;
    case VARCHAR:
      // Even in large (say VARCHAR(2000)) columns most strings are small
      return Math.min((double) type.getPrecision() * Character.BYTES, 100d);
    default:
      return null;
    }
  }

  /** Estimates the average size (in bytes) of a value of a type.
   *
   * <p>Nulls count as 1 byte.
   */
  public double typeValueSize(RelDataType type, Comparable value) {
    if (value == null) {
      return 1d;
    }
    switch (type.getSqlTypeName()) {
    case BOOLEAN:
    case TINYINT:
      return 1d;
    case SMALLINT:
      return 2d;
    case INTEGER:
    case FLOAT:
    case REAL:
    case DATE:
    case TIME:
      return 4d;
    case BIGINT:
    case DOUBLE:
    case TIMESTAMP:
    case INTERVAL_DAY_TIME:
    case INTERVAL_YEAR_MONTH:
      return 8d;
    case BINARY:
    case VARBINARY:
      return ((ByteString) value).length();
    case CHAR:
    case VARCHAR:
      return ((NlsString) value).getValue().length() * Character.BYTES;
    default:
      return 32;
    }
  }

  public Double averageRexSize(RexNode node, List<Double> inputColumnSizes) {
    switch (node.getKind()) {
    case INPUT_REF:
      return inputColumnSizes.get(((RexInputRef) node).getIndex());
    case LITERAL:
      return typeValueSize(node.getType(), ((RexLiteral) node).getValue());
    default:
      if (node instanceof RexCall) {
        RexCall call = (RexCall) node;
        for (RexNode operand : call.getOperands()) {
          // It's a reasonable assumption that a function's result will have
          // similar size to its argument of a similar type. For example,
          // UPPER(c) has the same average size as c.
          if (operand.getType().getSqlTypeName()
              == node.getType().getSqlTypeName()) {
            return averageRexSize(operand, inputColumnSizes);
          }
        }
      }
      return averageTypeValueSize(node.getType());
    }
  }
}

// End RelMdSize.java
