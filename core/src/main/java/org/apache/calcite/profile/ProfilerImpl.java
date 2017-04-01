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
package org.apache.calcite.profile;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.materialize.Lattice;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.rel.metadata.NullSentinel;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.runtime.PredicateImpl;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.PartiallyOrderedSet;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Implementation of {@link Profiler} that only investigates "interesting"
 * combinations of columns.
 */
public class ProfilerImpl implements Profiler {
  private static final Function<List<Comparable>, Comparable> ONLY =
      new Function<List<Comparable>, Comparable>() {
        public Comparable apply(List<Comparable> input) {
          return Iterables.getOnlyElement(input);
        }
      };

  /** The number of combinations to consider per pass.
   * The number is determined by memory, but a value of 1,000 is typical.
   * You need 2KB memory per sketch, and one sketch for each combination. */
  private final int combinationsPerPass;

  /** Whether a successor is considered interesting enough to analyze. */
  private final Predicate<Pair<Space, Column>> predicate;

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a {@code ProfilerImpl}.
   *
   * @param combinationsPerPass Maximum number of columns (or combinations of
   *   columns) to compute each pass
   * @param predicate Whether a successor is considered interesting enough to
   *   analyze
   */
  public ProfilerImpl(int combinationsPerPass,
      Predicate<Pair<Space, Column>> predicate) {
    Preconditions.checkArgument(combinationsPerPass > 2);
    this.combinationsPerPass = combinationsPerPass;
    this.predicate = predicate;
  }

  public Profile profile(Iterable<List<Comparable>> rows,
      final List<Column> columns) {
    return new Run(columns).profile(rows);
  }

  /** A run of the profiler. */
  class Run {
    private final List<Column> columns;
    final Map<ImmutableBitSet, Distribution> distributions = new HashMap<>();
    /** List of spaces that have one column. */
    final List<Space> singletonSpaces;
    /** Combinations of columns that we have computed but whose successors have
     * not yet been computed. We may add some of those successors to
     * {@link #spaceQueue}. */
    final Queue<Space> doneQueue =
        new PriorityQueue<>(100,
          new Comparator<Space>() {
            public int compare(Space s0, Space s1) {
              // The space with 0 columns is more interesting than
              // any space with 1 column, and so forth.
              // For spaces with 2 or more columns we compare "surprise":
              // how many fewer values did it have than expected?
              int c = Integer.compare(s0.columns.size(), s1.columns.size());
              if (c == 0) {
                c = Double.compare(s0.surprise(), s1.surprise());
              }
              return c;
            }
          });

    /** Combinations of columns that we will compute next pass. */
    final Deque<ImmutableBitSet> spaceQueue = new ArrayDeque<>();
    final List<Unique> uniques = new ArrayList<>();
    final List<FunctionalDependency> functionalDependencies = new ArrayList<>();
    final PartiallyOrderedSet.Ordering<Space> ordering =
        new PartiallyOrderedSet.Ordering<Space>() {
          public boolean lessThan(Space e1, Space e2) {
            return e2.columnOrdinals.contains(e1.columnOrdinals);
          }
        };
    /** Column ordinals that have ever been placed on {@link #spaceQueue}.
     * Ensures that we do not calculate the same combination more than once,
     * even though we generate a column set from multiple parents. */
    final Set<ImmutableBitSet> resultSet = new HashSet<>();
    final PartiallyOrderedSet<Space> results =
        new PartiallyOrderedSet<>(ordering);
    private final List<ImmutableBitSet> keyOrdinalLists =
        new ArrayList<>();
    final Function<Integer, Column> get =
        new Function<Integer, Column>() {
          public Column apply(Integer input) {
            return columns.get(input);
          }
        };
    private int rowCount;

    /**
     * Creates a Run.
     *
     * @param columns List of columns
     */
    Run(final List<Column> columns) {
      this.columns = ImmutableList.copyOf(columns);
      for (Ord<Column> column : Ord.zip(columns)) {
        if (column.e.ordinal != column.i) {
          throw new IllegalArgumentException();
        }
      }
      this.singletonSpaces =
          new ArrayList<>(Collections.nCopies(columns.size(), (Space) null));
      if (combinationsPerPass > Math.pow(2D, columns.size())) {
        // There are not many columns. We can compute all combinations in the
        // first pass.
        for (ImmutableBitSet ordinals
            : ImmutableBitSet.range(columns.size()).powerSet()) {
          spaceQueue.add(ordinals);
        }
      } else {
        // We will need to take multiple passes.
        // Pass 0, just put the empty combination on the queue.
        // Next pass, we will do its successors, the singleton combinations.
        spaceQueue.add(ImmutableBitSet.of());
      }
    }

    Profile profile(Iterable<List<Comparable>> rows) {
      int pass = 0;
      for (;;) {
        final List<Space> spaces = nextBatch(pass);
        if (spaces.isEmpty()) {
          break;
        }
        pass(pass++, spaces, rows);
      }

      for (Space s : singletonSpaces) {
        for (ImmutableBitSet dependent : s.dependents) {
          functionalDependencies.add(
              new FunctionalDependency(toColumns(dependent),
                  Iterables.getOnlyElement(s.columns)));
        }
      }
      return new Profile(columns, new RowCount(rowCount),
          functionalDependencies, distributions.values(), uniques);
    }

    /** Populates {@code spaces} with the next batch.
     * Returns an empty list if done. */
    List<Space> nextBatch(int pass) {
      final List<Space> spaces = new ArrayList<>();
    loop:
      for (;;) {
        if (spaces.size() >= combinationsPerPass) {
          // We have enough for the next pass.
          return spaces;
        }
        // First, see if there is a space we did have room for last pass.
        final ImmutableBitSet ordinals = spaceQueue.poll();
        if (ordinals != null) {
          final Space space = new Space(this, ordinals, toColumns(ordinals));
          spaces.add(space);
          if (ordinals.cardinality() == 1) {
            singletonSpaces.set(ordinals.nth(0), space);
          }
        } else {
          // Next, take a space that was done last time, generate its
          // successors, and add the interesting ones to the space queue.
          for (;;) {
            final Space doneSpace = doneQueue.poll();
            if (doneSpace == null) {
              // There are no more done spaces. We're done.
              return spaces;
            }
            for (Column column : columns) {
              if (!doneSpace.columnOrdinals.get(column.ordinal)) {
                if (pass == 0
                    || doneSpace.columnOrdinals.cardinality() == 0
                    || !containsKey(
                        doneSpace.columnOrdinals.set(column.ordinal))
                    && predicate.apply(Pair.of(doneSpace, column))) {
                  final ImmutableBitSet nextOrdinals =
                      doneSpace.columnOrdinals.set(column.ordinal);
                  if (resultSet.add(nextOrdinals)) {
                    spaceQueue.add(nextOrdinals);
                  }
                }
              }
            }
            // We've converted at a space into at least one interesting
            // successor.
            if (!spaceQueue.isEmpty()) {
              continue loop;
            }
          }
        }
      }
    }

    private boolean containsKey(ImmutableBitSet ordinals) {
      for (ImmutableBitSet keyOrdinals : keyOrdinalLists) {
        if (ordinals.contains(keyOrdinals)) {
          return true;
        }
      }
      return false;
    }

    void pass(int pass, List<Space> spaces, Iterable<List<Comparable>> rows) {
      System.out.println("pass: " + pass
          + ", spaces.size: " + spaces.size()
          + ", distributions.size: " + distributions.size());

      for (Space space : spaces) {
        space.collector = Collector.create(space);
      }

      int rowCount = 0;
      for (final List<Comparable> row : rows) {
        ++rowCount;
        for (Space space : spaces) {
          space.collector.add(row);
        }
      }

      // Populate unique keys.
      // If [x, y] is a key,
      // then [x, y, z] is a non-minimal key (therefore not interesting),
      // and [x, y] => [a] is a functional dependency but not interesting,
      // and [x, y, z] is not an interesting distribution.
      for (Space space : spaces) {
        space.collector.finish();
        space.collector = null;
        results.add(space);

        int nonMinimal = 0;
      dependents:
        for (Space s : results.getDescendants(space)) {
          if (s.cardinality == space.cardinality) {
            // We have discovered a sub-set that has the same cardinality.
            // The column(s) that are not in common are functionally
            // dependent.
            final ImmutableBitSet dependents =
                space.columnOrdinals.except(s.columnOrdinals);
            for (int i : s.columnOrdinals) {
              final Space s1 = singletonSpaces.get(i);
              final ImmutableBitSet rest = s.columnOrdinals.clear(i);
              for (ImmutableBitSet dependent : s1.dependents) {
                if (rest.contains(dependent)) {
                  // The "key" of this functional dependency is not minimal.
                  // For instance, if we know that
                  //   (a) -> x
                  // then
                  //   (a, b, x) -> y
                  // is not minimal; we could say the same with a smaller key:
                  //   (a, b) -> y
                  ++nonMinimal;
                  continue dependents;
                }
              }
            }
            for (int dependent : dependents) {
              final Space s1 = singletonSpaces.get(dependent);
              for (ImmutableBitSet d : s1.dependents) {
                if (s.columnOrdinals.contains(d)) {
                  ++nonMinimal;
                  continue dependents;
                }
              }
            }
            space.dependencies.or(dependents.toBitSet());
            for (int d : dependents) {
              singletonSpaces.get(d).dependents.add(s.columnOrdinals);
            }
          }
        }
        if (nonMinimal > 0) {
          continue;
        }
        double expectedCardinality;
        switch (space.columns.size()) {
        case 0:
          expectedCardinality = 1d;
          break;
        case 1:
          expectedCardinality = rowCount;
          break;
        default:
          expectedCardinality = rowCount;
          for (Column column : space.columns) {
            final Distribution d1 =
                distributions.get(ImmutableBitSet.of(column.ordinal));
            final Distribution d2 =
                distributions.get(space.columnOrdinals.clear(column.ordinal));
            if (d1 == null || d2 == null) {
              continue;
            }
            final double d =
                Lattice.getRowCount(rowCount, d1.cardinality, d2.cardinality);
            expectedCardinality = Math.min(expectedCardinality, d);
          }
        }

        final boolean minimal = nonMinimal == 0
            && !space.unique
            && !containsKey(space.columnOrdinals);
        space.expectedCardinality = expectedCardinality;
        final Distribution distribution =
            new Distribution(space.columns, space.valueSet, space.cardinality,
                space.nullCount, expectedCardinality, minimal);
        if (minimal && isInteresting(space)) {
          distributions.put(space.columnOrdinals, distribution);
          doneQueue.add(space);
        }

        if (space.cardinality == rowCount) {
          // We have discovered a new key. It is not a super-set of a key.
          uniques.add(new Unique(space.columns));
          keyOrdinalLists.add(space.columnOrdinals);
          space.unique = true;
        }
      }

      if (pass == 0) {
        this.rowCount = rowCount;
      }
    }

    private boolean isInteresting(Space space) {
      if (CalcitePrepareImpl.DEBUG) {
        System.out.println(space.columns
            + ", cardinality: " + space.cardinality
            + ", expected: " + space.expectedCardinality
            + ", surprise: " + space.surprise());
      }
      return space.columns.size() < 2
          || space.surprise() > 0.3D;
    }

    private ImmutableSortedSet<Column> toColumns(Iterable<Integer> ordinals) {
      return ImmutableSortedSet.copyOf(Iterables.transform(ordinals, get));
    }
  }

  /** Work space for a particular combination of columns. */
  static class Space {
    private final Run run;
    final ImmutableBitSet columnOrdinals;
    final ImmutableSortedSet<Column> columns;
    boolean unique;
    final BitSet dependencies = new BitSet();
    final Set<ImmutableBitSet> dependents = new HashSet<>();
    double expectedCardinality;
    Collector collector;
    /** Assigned by {@link Collector#finish()}. */
    int nullCount;
    /** Number of distinct values. Null is counted as a value, if present.
     * Assigned by {@link Collector#finish()}. */
    int cardinality;
    /** Assigned by {@link Collector#finish()}. */
    SortedSet<Comparable> valueSet;

    Space(Run run, ImmutableBitSet columnOrdinals, Iterable<Column> columns) {
      this.run = run;
      this.columnOrdinals = columnOrdinals;
      this.columns = ImmutableSortedSet.copyOf(columns);
    }

    @Override public int hashCode() {
      return columnOrdinals.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Space
          && columnOrdinals.equals(((Space) o).columnOrdinals);
    }

    /** Returns the distribution created from this space, or null if no
     * distribution has been registered yet. */
    public Distribution distribution() {
      return run.distributions.get(columnOrdinals);
    }

    public double surprise() {
      return SimpleProfiler.surprise(expectedCardinality, cardinality);
    }
  }

  /** Builds a {@link org.apache.calcite.profile.ProfilerImpl}. */
  public static class Builder {
    int combinationsPerPass = 100;
    Predicate<Pair<Space, Column>> predicate = Predicates.alwaysTrue();

    public ProfilerImpl build() {
      return new ProfilerImpl(combinationsPerPass, predicate);
    }

    public Builder withPassSize(int passSize) {
      this.combinationsPerPass = passSize;
      return this;
    }

    public Builder withMinimumSurprise(double v) {
      predicate =
          new PredicateImpl<Pair<Space, Column>>() {
            public boolean test(Pair<Space, Column> spaceColumnPair) {
              final Space space = spaceColumnPair.left;
              return false;
            }
          };
      return this;
    }
  }

  /** Collects values of a column or columns. */
  abstract static class Collector {
    protected final Space space;

    Collector(Space space) {
      this.space = space;
    }

    abstract void add(List<Comparable> row);
    abstract void finish();

    /** Creates an initial collector of the appropriate kind. */
    public static Collector create(Space space) {
      final List<Integer> columnOrdinalList = space.columnOrdinals.asList();
      if (columnOrdinalList.size() == 1) {
        return new SingletonCollector(space, columnOrdinalList.get(0));
      } else {
        return new CompositeCollector(space, columnOrdinalList);
      }
    }
  }

  /** Collector that collects values of a single column. */
  static class SingletonCollector extends Collector {
    final SortedSet<Comparable> values = new TreeSet<>();
    final int columnOrdinal;
    int nullCount = 0;

    SingletonCollector(Space space, int columnOrdinal) {
      super(space);
      this.columnOrdinal = columnOrdinal;
    }

    public void add(List<Comparable> row) {
      final Comparable v = row.get(columnOrdinal);
      if (v == NullSentinel.INSTANCE) {
        nullCount++;
      } else {
        values.add(v);
      }
    }

    public void finish() {
      space.nullCount = nullCount;
      space.cardinality = values.size() + (nullCount > 0 ? 1 : 0);
      space.valueSet = values.size() < 20 ? values : null;
    }
  }

  /** Collector that collects two or more column values in a tree set. */
  static class CompositeCollector extends Collector {
    final SortedSet<FlatLists.ComparableList> values = new TreeSet<>();
    final int[] columnOrdinals;
    final Comparable[] columnValues;
    int nullCount = 0;

    CompositeCollector(Space space, List<Integer> columnOrdinals) {
      super(space);
      this.columnOrdinals = (int[]) Primitive.INT.toArray(columnOrdinals);
      this.columnValues = new Comparable[columnOrdinals.size()];
    }

    public void add(List<Comparable> row) {
      for (int i = 0, length = columnOrdinals.length; i < length; i++) {
        final Comparable value = row.get(columnOrdinals[i]);
        if (value == NullSentinel.INSTANCE) {
          nullCount++;
          return;
        }
        columnValues[i] = value;
      }
      ((Set) values).add(FlatLists.copyOf(columnValues));
    }

    public void finish() {
      space.nullCount = -1;
      space.cardinality = values.size() + (nullCount > 0 ? 1 : 0);
      space.valueSet = null;
    }

  }

}

// End ProfilerImpl.java
