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
package org.apache.calcite.runtime;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.util.CircularArrayList;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

/** Unit tests for {@link Enumerables.Automaton}. */
public class AutomatonTest {
  @Test public void testSimple() {
    // pattern(a)
    final Graph g = new GraphBuilder().symbol("a").build();
    final String[] rows = {"", "a", "", "a"};
    final Matcher<String> matcher =
        Matcher.<String>builder(g).add("a", s -> s.contains("a")).build();
    final String expected = "[[a], [a]]";
    assertThat(matcher.match(rows).toString(), is(expected));
  }

  @Test public void testSequence() {
    // pattern(a b)
    final Graph g = new GraphBuilder().symbol("a").symbol("b").build();
    final String[] rows = {"", "a", "", "ab", "a", "ab", "b", "b"};
    final Matcher<String> matcher =
        Matcher.<String>builder(g)
            .add("a", s -> s.contains("a"))
            .add("b", s -> s.contains("b"))
            .build();
    final String expected = "[[a, ab], [ab, b]]";
    assertThat(matcher.match(rows).toString(), is(expected));
  }

  /** Builds a state-transition graph for deterministic finite automaton.
   * Helps us write tests for automata.
   * In production code, the automaton is created based on a parse tree.
   */
  static class GraphBuilder {
    final Stack<State> stateStack = new Stack<>();
    final Map<String, Integer> symbolIds = new HashMap<>();
    final List<State> stateList = new ArrayList<>();

    {
      final StartState startState = new StartState();
      addState(startState);
      stateStack.push(startState);
    }

    private void addState(State state) {
      Preconditions.checkArgument(state.id == stateList.size());
      stateList.add(state);
    }

    GraphBuilder symbol(String name) {
      Objects.requireNonNull(name);
      final State fromState = stateStack.pop();
      final int symbolId =
          symbolIds.computeIfAbsent(name, k -> symbolIds.size());
      final State state =
          new SymbolState(stateList.size(), fromState, symbolId);
      addState(state);
      stateStack.push(state);
      return this;
    }

    public Graph build() {
      final State endState = stateStack.pop();
      Preconditions.checkArgument(stateStack.empty());
      final ImmutableList.Builder<SymbolState> transitions =
          ImmutableList.builder();
      for (State state : stateList) {
        if (state instanceof SymbolState) {
          transitions.add((SymbolState) state);
        }
      }
      final ImmutableList<String> symbolNames = symbolIds.entrySet()
          .stream()
          .sorted(Comparator.comparingInt(Map.Entry::getValue))
          .map(Map.Entry::getKey)
          .collect(Util.toImmutableList());
      return new Graph((StartState) stateList.get(0), endState,
          transitions.build(), symbolNames);
    }
  }

  /** Node in the finite-state automaton. A state has a number of
   * transitions to other states, each labeled with the symbol that
   * casues that transition. */
  abstract static class State {
    final int id;

    State(int id) {
      this.id = id;
    }
  }

  /** The unique state in the automaton that is the starting point for
   * matching patterns. */
  static class StartState extends State {
    StartState() {
      super(0);
    }
  }

  /** Abstract base class for {@link State} instances that have a
   * predecessor state. */
  abstract static class SuccessorState extends State {
    final State fromState;

    SuccessorState(int id, State fromState) {
      super(id);
      this.fromState = Objects.requireNonNull(fromState);
    }
  }

  /** A state that is reached by transitioning an arc when a symbol is
   * read. */
  static class SymbolState extends SuccessorState {
    final int symbol;

    SymbolState(int id, State fromState, int symbol) {
      super(id, fromState);
      this.symbol = symbol;
    }
  }

  /** A finite-state automaton. */
  static class Graph {
    final StartState startState;
    final State endState;
    final ImmutableList<SymbolState> transitions;
    final ImmutableList<String> symbolNames;

    Graph(StartState startState, State endState,
        ImmutableList<SymbolState> transitions,
        ImmutableList<String> symbolNames) {
      this.startState = Objects.requireNonNull(startState);
      this.endState = Objects.requireNonNull(endState);
      this.transitions = Objects.requireNonNull(transitions);
      this.symbolNames = symbolNames;
    }

    /** Returns the set of states, represented as a bit set, that the graph is
     * in when starting from {@code state} and receiving {@code symbol}.
     *
     * @param fromState Initial state
     * @param symbol Symbol received
     * @param bitSet Set of successor states (output)
     */
    void successors(int fromState, int symbol,
        ImmutableBitSet.Builder bitSet) {
      for (SymbolState transition : transitions) {
        if (transition.fromState.id == fromState
            && transition.symbol == symbol) {
          bitSet.set(transition.id);
        }
      }
    }
  }

  /** Workspace that matches patterns against an automaton.
   *
   * @param <E> Type of rows matched by this automaton */
  static class Matcher<E> {
    private final Graph graph;
    private final ImmutableList<Predicate<E>> predicates;

    /** Creates a Matcher; use {@link #builder}. */
    private Matcher(Graph graph, ImmutableList<Predicate<E>> predicates) {
      this.graph = Objects.requireNonNull(graph);
      this.predicates = Objects.requireNonNull(predicates);
    }

    static <E> Builder<E> builder(Graph graph) {
      return new Builder<>(graph);
    }

    public List<List<E>> match(E... rows) {
      return match(Arrays.asList(rows));
    }

    public List<List<E>> match(Iterable<E> rows) {
      final ImmutableBitSet emptyStateSet = ImmutableBitSet.of();
      final ImmutableList.Builder<List<E>> resultMatches =
          ImmutableList.builder();
      final CircularArrayList<E> bufferedRows =
          new CircularArrayList<>();
      final CircularArrayList<ImmutableBitSet> stateSets =
          new CircularArrayList<>();
      final List<Pair<E, ImmutableBitSet>> rowStateSets =
          new MutableZipList<>(bufferedRows, stateSets);
      final ImmutableBitSet startSet = ImmutableBitSet.of(graph.startState.id);
      final List<Integer> rowSymbols = new ArrayList<>();
      final ImmutableBitSet.Builder nextStateBuilder = ImmutableBitSet.builder();
      for (E row : rows) {
        // Add this row to the states.
        rowStateSets.add(Pair.of(row, startSet));

        // Compute the set of symbols (i.e. predicates that evaluate to true)
        // for this row.
        rowSymbols.clear();
        for (Ord<Predicate<E>> predicate : Ord.zip(predicates)) {
          if (predicate.e.test(row)) {
            rowSymbols.add(predicate.i);
          }
        }

        // TODO: Should we short-cut if symbols is empty?

        // Now process the states of matches, oldest first, and compute the
        // successors based on the predicates that are true for the current
        // row.
        for (int i = 0; i < rowStateSets.size();) {
          final Pair<E, ImmutableBitSet> rowStateSet = rowStateSets.get(i);
          assert nextStateBuilder.isEmpty();
          for (int symbol : rowSymbols) {
            for (int state : rowStateSet.right) {
              graph.successors(state, symbol, nextStateBuilder);
            }
          }
          final ImmutableBitSet nextStateSet =
              nextStateBuilder.buildAndReset();
          if (nextStateSet.isEmpty()) {
            if (i == 0) {
              // Don't add the stateSet if it is empty and would be the oldest.
              // The first item in stateSets must not be empty.
              rowStateSets.remove(0);
            } else {
              rowStateSets.set(i++, Pair.of(row, emptyStateSet));
            }
          } else if (nextStateSet.get(graph.endState.id)) {
            resultMatches.add(
                ImmutableList.copyOf(
                    bufferedRows.subList(i, bufferedRows.size())));
            if (i == 0) {
              // Don't add the stateSet if it is empty and would be the oldest.
              // The first item in stateSets must not be empty.
              rowStateSets.remove(0);
            } else {
              // Set state to empty so that it is not considered for any
              // further matches, and will be removed when it is the oldest.
              rowStateSets.set(i++, Pair.of(row, emptyStateSet));
            }
          } else {
            rowStateSets.set(i++, Pair.of(row, nextStateSet));
          }
        }
      }
      return resultMatches.build();
    }

    /** Builds a Matcher.
     *
     * @param <E> Type of rows matched by this automaton */
    static class Builder<E> {
      final Graph graph;
      final Map<String, Predicate<E>> symbolPredicates =
          new HashMap<>();

      Builder(Graph graph) {
        this.graph = graph;
      }

      Builder<E> add(String symbolName, Predicate<E> predicate) {
        symbolPredicates.put(symbolName, predicate);
        return this;
      }

      public Matcher<E> build() {
        final Set<String> graphSymbolNames = new TreeSet<>(graph.symbolNames);
        if (!symbolPredicates.keySet().equals(graphSymbolNames)) {
          throw new IllegalArgumentException("not all symbols in the graph ["
              + graphSymbolNames + "] have predicates ["
              + symbolPredicates.keySet() + "]");
        }
        final ImmutableList.Builder<Predicate<E>> builder =
            ImmutableList.builder();
        for (String symbolName : graph.symbolNames) {
          builder.add(symbolPredicates.get(symbolName));
        }
        return new Matcher<>(graph, builder.build());
      }
    }
  }

  /** A mutable list of pairs backed by a pair of mutable lists.
   *
   * <p>Modifications to this list are reflected in the backing lists, and vice
   * versa. */
  private static class MutableZipList<K, V> extends AbstractList<Pair<K, V>> {
    private final List<K> lefts;
    private final List<V> rights;

    MutableZipList(List<K> lefts, List<V> rights) {
      this.lefts = Objects.requireNonNull(lefts);
      this.rights = Objects.requireNonNull(rights);
    }

    @Override public Pair<K, V> get(int index) {
      return Pair.of(lefts.get(index), rights.get(index));
    }

    @Override public int size() {
      return Math.min(lefts.size(), rights.size());
    }

    @Override public void add(int index, Pair<K, V> pair) {
      lefts.add(index, pair.left);
      rights.add(index, pair.right);
    }

    @Override public Pair<K, V> remove(int index) {
      final K bufferedRow = lefts.remove(index);
      final V stateSet = rights.remove(index);
      return Pair.of(bufferedRow, stateSet);
    }

    @Override public Pair<K, V> set(int index, Pair<K, V> pair) {
      final Pair<K, V> previous = get(index);
      lefts.set(index, pair.left);
      rights.set(index, pair.right);
      return previous;
    }
  }
}

// End AutomatonTest.java