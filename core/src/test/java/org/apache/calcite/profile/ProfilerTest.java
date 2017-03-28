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

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.metadata.NullSentinel;
import org.apache.calcite.runtime.PredicateImpl;
import org.apache.calcite.test.CalciteAssert;
import org.apache.calcite.test.Matchers;
import org.apache.calcite.util.JsonBuilder;
import org.apache.calcite.util.Pair;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import org.hamcrest.Matcher;
import org.junit.Assert;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Basic implementation of {@link Profiler}.
 */
public class ProfilerTest {
  @Test public void testProfileZeroRows() throws Exception {
    final String sql = "select * from \"scott\".dept where false";
    sql(sql).unordered(
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:0.0}",
        "{type:distribution,columns:[DEPTNO],values:[],cardinality:0.0}",
        "{type:distribution,columns:[DNAME],values:[],cardinality:0.0}",
        "{type:distribution,columns:[],cardinality:0.0}",
        "{type:rowCount,rowCount:0}",
        "{type:unique,columns:[]}");
  }

  @Test public void testProfileOneRow() throws Exception {
    final String sql = "select * from \"scott\".dept where deptno = 10";
    sql(sql).unordered(
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:1.0}",
        "{type:distribution,columns:[DEPTNO],values:[10],cardinality:1.0}",
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING],cardinality:1.0}",
        "{type:distribution,columns:[],cardinality:1.0}",
        "{type:rowCount,rowCount:1}",
        "{type:unique,columns:[]}");
  }

  @Test public void testProfileTwoRows() throws Exception {
    final String sql = "select * from \"scott\".dept where deptno in (10, 20)";
    sql(sql).unordered(
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:2.0}",
        "{type:distribution,columns:[DEPTNO],values:[10,20],cardinality:2.0}",
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING,RESEARCH],cardinality:2.0}",
        "{type:distribution,columns:[],cardinality:1.0}",
        "{type:rowCount,rowCount:2}",
        "{type:unique,columns:[DEPTNO]}",
        "{type:unique,columns:[DNAME]}");
  }

  @Test public void testProfileScott() throws Exception {
    final String sql = "select * from \"scott\".emp\n"
        + "join \"scott\".dept using (deptno)";
    sql(sql)
        .where(new PredicateImpl<Profiler.Statistic>() {
          public boolean test(Profiler.Statistic statistic) {
            return !(statistic instanceof Profiler.Distribution)
                || ((Profiler.Distribution) statistic).cardinality < 14
                && ((Profiler.Distribution) statistic).minimal;
          }
        }).unordered(
        "{type:distribution,columns:[COMM,DEPTNO0],cardinality:4.0}",
        "{type:distribution,columns:[COMM,DEPTNO],cardinality:4.0}",
        "{type:distribution,columns:[COMM,DNAME],cardinality:4.0}",
        "{type:distribution,columns:[COMM],values:[0.00,300.00,500.00,1400.00],cardinality:4.0,nullCount:10}",
        "{type:distribution,columns:[DEPTNO,DEPTNO0],cardinality:3.0}",
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:3.0}",
        "{type:distribution,columns:[DEPTNO0,DNAME],cardinality:3.0}",
        "{type:distribution,columns:[DEPTNO0],values:[10,20,30],cardinality:3.0}",
        "{type:distribution,columns:[DEPTNO],values:[10,20,30],cardinality:3.0}",
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING,RESEARCH,SALES],cardinality:3.0}",
        "{type:distribution,columns:[HIREDATE,COMM],cardinality:4.0}",
        "{type:distribution,columns:[HIREDATE],values:[1980-12-17,1981-01-05,1981-02-04,1981-02-20,1981-02-22,1981-06-09,1981-09-08,1981-09-28,1981-11-17,1981-12-03,1982-01-23,1987-04-19,1987-05-23],cardinality:13.0}",
        "{type:distribution,columns:[JOB,COMM],cardinality:4.0}",
        "{type:distribution,columns:[JOB,DEPTNO0],cardinality:9.0}",
        "{type:distribution,columns:[JOB,DEPTNO],cardinality:9.0}",
        "{type:distribution,columns:[JOB,DNAME],cardinality:9.0}",
        "{type:distribution,columns:[JOB,MGR,DEPTNO0],cardinality:9.0}",
        "{type:distribution,columns:[JOB,MGR,DEPTNO],cardinality:9.0}",
        "{type:distribution,columns:[JOB,MGR,DNAME],cardinality:9.0}",
        "{type:distribution,columns:[JOB,MGR],cardinality:7.0}",
        "{type:distribution,columns:[JOB,SAL],cardinality:12.0}",
        "{type:distribution,columns:[JOB],values:[ANALYST,CLERK,MANAGER,PRESIDENT,SALESMAN],cardinality:5.0}",
        "{type:distribution,columns:[MGR,COMM],cardinality:4.0}",
        "{type:distribution,columns:[MGR,DEPTNO0],cardinality:8.0}",
        "{type:distribution,columns:[MGR,DEPTNO],cardinality:8.0}",
        "{type:distribution,columns:[MGR,DNAME],cardinality:8.0}",
        "{type:distribution,columns:[MGR,HIREDATE],cardinality:13.0}",
        "{type:distribution,columns:[MGR,SAL],cardinality:11.0}",
        "{type:distribution,columns:[MGR],values:[7566,7698,7782,7788,7839,7902],cardinality:6.0,nullCount:1}",
        "{type:distribution,columns:[SAL,COMM],cardinality:4.0}",
        "{type:distribution,columns:[SAL,DEPTNO0],cardinality:12.0}",
        "{type:distribution,columns:[SAL,DEPTNO],cardinality:12.0}",
        "{type:distribution,columns:[SAL,DNAME],cardinality:12.0}",
        "{type:distribution,columns:[SAL],values:[800.00,950.00,1100.00,1250.00,1300.00,1500.00,1600.00,2450.00,2850.00,2975.00,3000.00,5000.00],cardinality:12.0}",
        "{type:distribution,columns:[],cardinality:1.0}",
        "{type:fd,columns:[DEPTNO0],dependentColumn:DEPTNO}",
        "{type:fd,columns:[DEPTNO0],dependentColumn:DNAME}",
        "{type:fd,columns:[DEPTNO],dependentColumn:DEPTNO0}",
        "{type:fd,columns:[DEPTNO],dependentColumn:DNAME}",
        "{type:fd,columns:[DNAME],dependentColumn:DEPTNO0}",
        "{type:fd,columns:[DNAME],dependentColumn:DEPTNO}",
        "{type:fd,columns:[HIREDATE],dependentColumn:MGR}",
        "{type:fd,columns:[JOB,DEPTNO0],dependentColumn:MGR}",
        "{type:fd,columns:[JOB,DEPTNO],dependentColumn:MGR}",
        "{type:fd,columns:[JOB,DNAME],dependentColumn:MGR}",
        "{type:fd,columns:[SAL],dependentColumn:DEPTNO0}",
        "{type:fd,columns:[SAL],dependentColumn:DEPTNO}",
        "{type:fd,columns:[SAL],dependentColumn:DNAME}",
        "{type:fd,columns:[SAL],dependentColumn:JOB}",
        "{type:rowCount,rowCount:14}",
        "{type:unique,columns:[EMPNO]}",
        "{type:unique,columns:[ENAME]}",
        "{type:unique,columns:[HIREDATE,DEPTNO0]}",
        "{type:unique,columns:[HIREDATE,DEPTNO]}",
        "{type:unique,columns:[HIREDATE,DNAME]}",
        "{type:unique,columns:[HIREDATE,SAL]}",
        "{type:unique,columns:[JOB,HIREDATE]}");
  }

  /** As {@link #testProfileScott()}, but prints only the most surprising
   * distributions. */
  @Test public void testProfileScott2() throws Exception {
    fluid().factory(Fluid.SIMPLE_FACTORY).unordered(
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING,RESEARCH,SALES],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DEPTNO0],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DEPTNO],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[COMM],values:[0.00,300.00,500.00,1400.00],cardinality:4.0,nullCount:10,expectedCardinality:14.0,surprise:0.5555555555555556}",
        "{type:distribution,columns:[HIREDATE,COMM],cardinality:4.0,expectedCardinality:12.377762384970014,surprise:0.5115327837860406}",
        "{type:distribution,columns:[SAL,COMM],cardinality:4.0,expectedCardinality:12.253467117178234,surprise:0.5077973245754547}",
        "{type:distribution,columns:[JOB],values:[ANALYST,CLERK,MANAGER,PRESIDENT,SALESMAN],cardinality:5.0,expectedCardinality:14.0,surprise:0.47368421052631576}",
        "{type:distribution,columns:[MGR,COMM],cardinality:4.0,expectedCardinality:10.773541853578294,surprise:0.4584913977102706}",
        "{type:distribution,columns:[JOB,COMM],cardinality:4.0,expectedCardinality:10.246500417689411,surprise:0.43845858523496317}",
        "{type:distribution,columns:[DEPTNO0,DNAME],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[DEPTNO,DEPTNO0],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[MGR],values:[7566,7698,7782,7788,7839,7902],cardinality:6.0,nullCount:1,expectedCardinality:14.0,surprise:0.4}",
        "{type:distribution,columns:[SAL],values:[800.00,950.00,1100.00,1250.00,1300.00,1500.00,1600.00,2450.00,2850.00,2975.00,3000.00,5000.00],cardinality:12.0,expectedCardinality:14.0,surprise:0.07692307692307693}",
        "{type:distribution,columns:[HIREDATE],values:[1980-12-17,1981-01-05,1981-02-04,1981-02-20,1981-02-22,1981-06-09,1981-09-08,1981-09-28,1981-11-17,1981-12-03,1982-01-23,1987-04-19,1987-05-23],cardinality:13.0,expectedCardinality:14.0,surprise:0.037037037037037035}",
        "{type:distribution,columns:[],cardinality:1.0,expectedCardinality:1.0,surprise:0.0}");
  }

  /** As {@link #testProfileScott2()}, but uses the breadth-first profiler.
   * Results should be the same, but are slightly different (extra EMPNO
   * and ENAME distributions). */
  @Test public void testProfileScott3() throws Exception {
    fluid().factory(Fluid.BETTER_FACTORY).unordered(
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING,RESEARCH,SALES],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DEPTNO0],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DEPTNO],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[EMPNO],values:[7369,7499,7521,7566,7654,7698,7782,7788,7839,7844,7876,7900,7902,7934],cardinality:14.0,expectedCardinality:14.0,surprise:0.0}",
        "{type:distribution,columns:[ENAME],values:[ADAMS,ALLEN,BLAKE,CLARK,FORD,JAMES,JONES,KING,MARTIN,MILLER,SCOTT,SMITH,TURNER,WARD],cardinality:14.0,expectedCardinality:14.0,surprise:0.0}",
        "{type:distribution,columns:[COMM],values:[0.00,300.00,500.00,1400.00],cardinality:4.0,nullCount:10,expectedCardinality:14.0,surprise:0.5555555555555556}",
        "{type:distribution,columns:[HIREDATE,COMM],cardinality:4.0,expectedCardinality:12.377762384970014,surprise:0.5115327837860406}",
        "{type:distribution,columns:[SAL,COMM],cardinality:4.0,expectedCardinality:12.253467117178234,surprise:0.5077973245754547}",
        "{type:distribution,columns:[JOB],values:[ANALYST,CLERK,MANAGER,PRESIDENT,SALESMAN],cardinality:5.0,expectedCardinality:14.0,surprise:0.47368421052631576}",
        "{type:distribution,columns:[MGR,COMM],cardinality:4.0,expectedCardinality:10.773541853578294,surprise:0.4584913977102706}",
        "{type:distribution,columns:[JOB,COMM],cardinality:4.0,expectedCardinality:10.246500417689411,surprise:0.43845858523496317}",
        "{type:distribution,columns:[DEPTNO0,DNAME],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[DEPTNO,DEPTNO0],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[MGR],values:[7566,7698,7782,7788,7839,7902],cardinality:6.0,nullCount:1,expectedCardinality:14.0,surprise:0.4}",
        "{type:distribution,columns:[SAL],values:[800.00,950.00,1100.00,1250.00,1300.00,1500.00,1600.00,2450.00,2850.00,2975.00,3000.00,5000.00],cardinality:12.0,expectedCardinality:14.0,surprise:0.07692307692307693}",
        "{type:distribution,columns:[HIREDATE],values:[1980-12-17,1981-01-05,1981-02-04,1981-02-20,1981-02-22,1981-06-09,1981-09-08,1981-09-28,1981-11-17,1981-12-03,1982-01-23,1987-04-19,1987-05-23],cardinality:13.0,expectedCardinality:14.0,surprise:0.037037037037037035}",
        "{type:distribution,columns:[],cardinality:1.0,expectedCardinality:1.0,surprise:0.0}");
  }

  /** As {@link #testProfileScott3()}, but uses the breadth-first profiler
   * and deems everything uninteresting. Only first-level combinations (those
   * consisting of a single column) are computed. */
  @Test public void testProfileScott4() throws Exception {
    final Supplier<Profiler> factory = new Supplier<Profiler>() {
      public Profiler get() {
        return new ProfilerImpl(10,
            new PredicateImpl<Pair<ProfilerImpl.Space, Profiler.Column>>() {
              public boolean test(Pair<ProfilerImpl.Space, Profiler.Column> p) {
                return false;
              }
            });
      }
    };
    fluid().factory(factory).unordered(
        "{type:distribution,columns:[DEPTNO],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DEPTNO0],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[EMPNO],values:[7369,7499,7521,7566,7654,7698,7782,7788,7839,7844,7876,7900,7902,7934],cardinality:14.0,expectedCardinality:14.0,surprise:0.0}",
        "{type:distribution,columns:[ENAME],values:[ADAMS,ALLEN,BLAKE,CLARK,FORD,JAMES,JONES,KING,MARTIN,MILLER,SCOTT,SMITH,TURNER,WARD],cardinality:14.0,expectedCardinality:14.0,surprise:0.0}",
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING,RESEARCH,SALES],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[COMM],values:[0.00,300.00,500.00,1400.00],cardinality:4.0,nullCount:10,expectedCardinality:14.0,surprise:0.5555555555555556}",
        "{type:distribution,columns:[JOB],values:[ANALYST,CLERK,MANAGER,PRESIDENT,SALESMAN],cardinality:5.0,expectedCardinality:14.0,surprise:0.47368421052631576}",
        "{type:distribution,columns:[HIREDATE],values:[1980-12-17,1981-01-05,1981-02-04,1981-02-20,1981-02-22,1981-06-09,1981-09-08,1981-09-28,1981-11-17,1981-12-03,1982-01-23,1987-04-19,1987-05-23],cardinality:13.0,expectedCardinality:14.0,surprise:0.037037037037037035}",
        "{type:distribution,columns:[SAL],values:[800.00,950.00,1100.00,1250.00,1300.00,1500.00,1600.00,2450.00,2850.00,2975.00,3000.00,5000.00],cardinality:12.0,expectedCardinality:14.0,surprise:0.07692307692307693}",
        "{type:distribution,columns:[MGR],values:[7566,7698,7782,7788,7839,7902],cardinality:6.0,nullCount:1,expectedCardinality:14.0,surprise:0.4}",
        "{type:distribution,columns:[],cardinality:1.0,expectedCardinality:1.0,surprise:0.0}");
  }

  /** As {@link #testProfileScott3()}, but uses the breadth-first profiler
   * and deems everything uninteresting. Only first-level combinations (those
   * consisting of a single column) are computed. */
  @Test public void testProfileScott5() throws Exception {
    final Supplier<Profiler> factory = new Supplier<Profiler>() {
      public Profiler get() {
        return new ProfilerImpl(100,
            new PredicateImpl<Pair<ProfilerImpl.Space, Profiler.Column>>() {
              public boolean test(Pair<ProfilerImpl.Space, Profiler.Column> p) {
                final Profiler.Distribution distribution =
                    p.left.distribution();
                if (distribution == null) {
                  // We don't have a distribution yet, because this space has
                  // not yet been evaluated. Let's do it anyway.
                  return true;
                }
                return distribution.surprise() >= 0.3D;
              }
            });
      }
    };
    fluid().factory(factory).unordered(
        "{type:distribution,columns:[DEPTNO],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DEPTNO0],values:[10,20,30],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[DNAME],values:[ACCOUNTING,RESEARCH,SALES],cardinality:3.0,expectedCardinality:14.0,surprise:0.6470588235294118}",
        "{type:distribution,columns:[COMM],values:[0.00,300.00,500.00,1400.00],cardinality:4.0,nullCount:10,expectedCardinality:14.0,surprise:0.5555555555555556}",
        "{type:distribution,columns:[JOB],values:[ANALYST,CLERK,MANAGER,PRESIDENT,SALESMAN],cardinality:5.0,expectedCardinality:14.0,surprise:0.47368421052631576}",
        "{type:distribution,columns:[MGR],values:[7566,7698,7782,7788,7839,7902],cardinality:6.0,nullCount:1,expectedCardinality:14.0,surprise:0.4}",
        "{type:distribution,columns:[DEPTNO,DEPTNO0],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[DEPTNO,DNAME],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[DEPTNO0,DNAME],cardinality:3.0,expectedCardinality:7.269756624410332,surprise:0.41576025416819384}",
        "{type:distribution,columns:[EMPNO],values:[7369,7499,7521,7566,7654,7698,7782,7788,7839,7844,7876,7900,7902,7934],cardinality:14.0,expectedCardinality:14.0,surprise:0.0}",
        "{type:distribution,columns:[ENAME],values:[ADAMS,ALLEN,BLAKE,CLARK,FORD,JAMES,JONES,KING,MARTIN,MILLER,SCOTT,SMITH,TURNER,WARD],cardinality:14.0,expectedCardinality:14.0,surprise:0.0}",
        "{type:distribution,columns:[HIREDATE,COMM],cardinality:4.0,expectedCardinality:12.377762384970014,surprise:0.5115327837860406}",
        "{type:distribution,columns:[HIREDATE],values:[1980-12-17,1981-01-05,1981-02-04,1981-02-20,1981-02-22,1981-06-09,1981-09-08,1981-09-28,1981-11-17,1981-12-03,1982-01-23,1987-04-19,1987-05-23],cardinality:13.0,expectedCardinality:14.0,surprise:0.037037037037037035}",
        "{type:distribution,columns:[JOB,COMM],cardinality:4.0,expectedCardinality:10.246500417689411,surprise:0.43845858523496317}",
        "{type:distribution,columns:[MGR,COMM],cardinality:4.0,expectedCardinality:10.773541853578294,surprise:0.4584913977102706}",
        "{type:distribution,columns:[SAL,COMM],cardinality:4.0,expectedCardinality:12.253467117178234,surprise:0.5077973245754547}",
        "{type:distribution,columns:[SAL],values:[800.00,950.00,1100.00,1250.00,1300.00,1500.00,1600.00,2450.00,2850.00,2975.00,3000.00,5000.00],cardinality:12.0,expectedCardinality:14.0,surprise:0.07692307692307693}",
        "{type:distribution,columns:[],cardinality:1.0,expectedCardinality:1.0,surprise:0.0}");
  }

  private Fluid fluid() throws Exception {
    final String sql = "select * from \"scott\".emp\n"
        + "join \"scott\".dept using (deptno)";
    final List<String> columns =
        ImmutableList.<String>builder().addAll(Fluid.DEFAULT_COLUMNS)
            .add("expectedCardinality", "surprise")
            .build();
    final Ordering<Profiler.Statistic> ordering =
        new Ordering<Profiler.Statistic>() {
          public int compare(Profiler.Statistic left,
              Profiler.Statistic right) {
            int c = left.getClass().getSimpleName()
                .compareTo(right.getClass().getSimpleName());
            if (c == 0
                && left instanceof Profiler.Distribution
                && right instanceof Profiler.Distribution) {
              final Profiler.Distribution d0 = (Profiler.Distribution) left;
              final Profiler.Distribution d1 = (Profiler.Distribution) right;
              c = Double.compare(d0.surprise(), d1.surprise());
              if (c == 0) {
                c = d0.columns.toString().compareTo(d1.columns.toString());
              }
            }
            return c;
          }
        };
    final Predicate<Profiler.Statistic> predicate =
        new PredicateImpl<Profiler.Statistic>() {
          public boolean test(Profiler.Statistic statistic) {
            // Include distributions of zero columns (the grand total)
            // and singleton columns, plus "surprising" distributions
            // (with significantly higher NDVs than predicted from their
            // constituent columns).
            return statistic instanceof Profiler.Distribution
                && (((Profiler.Distribution) statistic).columns.size() < 2
                    || ((Profiler.Distribution) statistic).surprise() > 0.4D)
                && ((Profiler.Distribution) statistic).minimal;
          }
        };
    return sql(sql)
        .where(predicate)
        .sort(ordering.reverse())
        .limit(30)
        .project(columns);
  }

  private static Fluid sql(String sql) {
    return new Fluid(sql, Fluid.SIMPLE_FACTORY,
        Predicates.<Profiler.Statistic>alwaysTrue(), null, -1,
        Fluid.DEFAULT_COLUMNS);
  }

  /** Fluid interface for writing profiler test cases. */
  private static class Fluid {
    static final Supplier<Profiler> SIMPLE_FACTORY =
        new Supplier<Profiler>() {
          public Profiler get() {
            return new SimpleProfiler();
          }
        };

    static final Supplier<Profiler> BETTER_FACTORY =
        new Supplier<Profiler>() {
          public Profiler get() {
            final Predicate<Pair<ProfilerImpl.Space, Profiler.Column>>
                predicate = Predicates.alwaysTrue();
            return new ProfilerImpl(600, predicate);
          }
        };

    private final String sql;
    private final List<String> columns;

    static final List<String> DEFAULT_COLUMNS =
        ImmutableList.of("type", "distribution", "columns", "cardinality",
            "values", "nullCount", "dependentColumn", "rowCount");
    private final Comparator<Profiler.Statistic> comparator;
    private final int limit;
    private final Predicate<Profiler.Statistic> predicate;
    private final Supplier<Profiler> factory;

    Fluid(String sql, Supplier<Profiler> factory,
        Predicate<Profiler.Statistic> predicate,
        Comparator<Profiler.Statistic> comparator, int limit,
        List<String> columns) {
      this.sql = Preconditions.checkNotNull(sql);
      this.factory = Preconditions.checkNotNull(factory);
      this.columns = ImmutableList.copyOf(columns);
      this.predicate = Preconditions.checkNotNull(predicate);
      this.comparator = comparator; // null means sort on JSON representation
      this.limit = limit;
    }

    Fluid factory(Supplier<Profiler> factory) {
      return new Fluid(sql, factory, predicate, comparator, limit, columns);
    }

    Fluid project(List<String> columns) {
      return new Fluid(sql, factory, predicate, comparator, limit, columns);
    }

    Fluid sort(Ordering<Profiler.Statistic> comparator) {
      return new Fluid(sql, factory, predicate, comparator, limit, columns);
    }

    Fluid limit(int limit) {
      return new Fluid(sql, factory, predicate, comparator, limit, columns);
    }

    Fluid where(Predicate<Profiler.Statistic> predicate) {
      return new Fluid(sql, factory, predicate, comparator, limit, columns);
    }

    Fluid unordered(String... lines) throws Exception {
      return check(Matchers.equalsUnordered(lines));
    }

    public Fluid check(final Matcher<Iterable<String>> matcher)
        throws Exception {
      CalciteAssert.that(CalciteAssert.Config.SCOTT)
          .doWithConnection(new Function<CalciteConnection, Void>() {
            public Void apply(CalciteConnection c) {
              try (PreparedStatement s = c.prepareStatement(sql)) {
                final ResultSetMetaData m = s.getMetaData();
                final List<Profiler.Column> columns = new ArrayList<>();
                final int columnCount = m.getColumnCount();
                for (int i = 1; i < columnCount; i++) {
                  columns.add(new Profiler.Column(i - 1, m.getColumnLabel(i)));
                }
                final Profiler p = factory.get();
                final Enumerable<List<Comparable>> rows = getRows(s);
                final Profiler.Profile profile = p.profile(rows, columns);
                final List<Profiler.Statistic> statistics =
                    ImmutableList.copyOf(
                        Iterables.filter(profile.statistics(), predicate));

                // If no comparator specified, use the function that converts to
                // JSON strings
                final Function<Profiler.Statistic, String> toJson =
                    toJsonFunction();
                Comparator<Profiler.Statistic> comp = comparator != null
                    ? comparator
                    : Ordering.natural().onResultOf(toJson);
                Collections.sort(statistics, comp);
                if (limit >= 0 && limit < statistics.size()) {
                  statistics.subList(limit, statistics.size()).clear();
                }

                final List<String> strings =
                    Lists.transform(statistics, toJson);
                Assert.assertThat(strings, matcher);
              } catch (SQLException e) {
                throw new RuntimeException(e);
              }
              return null;
            }
          });
      return this;
    }

    /** Returns a function that converts a statistic to a JSON string. */
    Function<Profiler.Statistic, String> toJsonFunction() {
      return new Function<Profiler.Statistic, String>() {
        final JsonBuilder jb = new JsonBuilder();

        public String apply(Profiler.Statistic statistic) {
          Object map = statistic.toMap(jb);
          if (map instanceof Map) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map1 = (Map) map;
            map1.keySet().retainAll(Fluid.this.columns);
          }
          final String json = jb.toJsonString(map);
          return json.replaceAll("\n", "").replaceAll(" ", "")
              .replaceAll("\"", "");
        }
      };
    }

    private Enumerable<List<Comparable>> getRows(final PreparedStatement s) {
      return new AbstractEnumerable<List<Comparable>>() {
        public Enumerator<List<Comparable>> enumerator() {
          try {
            final ResultSet r = s.executeQuery();
            return getListEnumerator(r, r.getMetaData().getColumnCount());
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }
      };
    }

    private Enumerator<List<Comparable>> getListEnumerator(
        final ResultSet r, final int columnCount) {
      return new Enumerator<List<Comparable>>() {
        final Comparable[] values = new Comparable[columnCount];

        public List<Comparable> current() {
          for (int i = 0; i < columnCount; i++) {
            try {
              final Comparable value = (Comparable) r.getObject(i + 1);
              values[i] = NullSentinel.mask(value);
            } catch (SQLException e) {
              throw new RuntimeException(e);
            }
          }
          return ImmutableList.copyOf(values);
        }

        public boolean moveNext() {
          try {
            return r.next();
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }

        public void reset() {
        }

        public void close() {
          try {
            r.close();
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }
      };
    }
  }
}

// End ProfilerTest.java
