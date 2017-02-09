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
package org.apache.calcite.adapter.file;

import com.google.common.base.Function;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/**
 * System test of the Calcite file adapter, which can also read and parse
 * HTML tables over HTTP.
 */
public class SqlTest {
  // helper functions

  private void checkSql(String model, String sql, final String... lines)
      throws SQLException {
    final StringBuilder b = new StringBuilder();
    for (String s : lines) {
      b.append(s).append('\n');
    }
    final String expected = b.toString();
    checkSql(sql, model, new Function<ResultSet, Void>() {
      public Void apply(ResultSet resultSet) {
        try {
          String actual = SqlTest.toString(resultSet);
          if (!expected.equals(actual)) {
            System.out.println("Assertion failure:");
            System.out.println("\tExpected: '" + expected + "'");
            System.out.println("\tActual: '" + actual + "'");
          }
          assertEquals(expected, actual);
        } catch (SQLException e) {
          throw new RuntimeException(e);
        }
        return null;
      }
    });
  }

  private void checkSql(String sql, String model, Function<ResultSet, Void> fn)
      throws SQLException {
    Connection connection = null;
    Statement statement = null;
    try {
      Properties info = new Properties();
      info.put("model",
          FileReaderTest.file("target/test-classes/" + model + ".json"));
      connection = DriverManager.getConnection("jdbc:calcite:", info);
      statement = connection.createStatement();
      final ResultSet resultSet = statement.executeQuery(sql);
      fn.apply(resultSet);
    } finally {
      close(connection, statement);
    }
  }

  private static String toString(ResultSet resultSet) throws SQLException {
    StringBuilder buf = new StringBuilder();
    while (resultSet.next()) {
      int n = resultSet.getMetaData().getColumnCount();
      String sep = "";
      for (int i = 1; i <= n; i++) {
        buf.append(sep)
            .append(resultSet.getMetaData().getColumnLabel(i))
            .append("=")
            .append(resultSet.getObject(i));
        sep = "; ";
      }
      buf.append("\n");
    }
    return buf.toString();
  }

  private void close(Connection connection, Statement statement) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  // tests

  /** Reads from a local file and checks the result. */
  @Test public void testFileSelect() throws SQLException {
    final String sql = "select H1 from T1 where H0 = 'R1C0'";
    checkSql("testModel", sql, "H1=R1C1");
  }

  /** Reads from a local file without table headers &lt;TH&gt; and checks the
   * result. */
  @Test public void testNoThSelect() throws SQLException {
    Assume.assumeTrue(FileSuite.hazNetwork());
    final String sql = "select \"col1\" from T1_NO_TH where \"col0\" like 'R0%'";
    checkSql("testModel", sql, "col1=R0C1");
  }

  /** Reads from a local file - finds larger table even without &lt;TH&gt;
   * elements. */
  @Test public void testFindBiggerNoTh() throws SQLException {
    final String sql = "select \"col4\" from TABLEX2 where \"col0\" like 'R1%'";
    checkSql("testModel", sql, "col4=R1C4");
  }

  /** Reads from a URL and checks the result. */
  @Ignore
  @Test public void testUrlSelect() throws SQLException {
    Assume.assumeTrue(FileSuite.hazNetwork());
    final String sql = "select \"State\", \"Statehood\" from \"States\"\n"
        + "where \"State\" = 'California'";
    checkSql("wiki", sql, "State=California; Statehood=1850-09-09");
  }

  /** Reads the EMPS table. */
  @Test public void testSalesEmps() throws SQLException {
    final String sql = "select * from sales.emps";
    checkSql("sales", sql,
        "EMPNO=100; DEPTNO=30; NAME=Fred",
        "EMPNO=110; DEPTNO=20; NAME=Eric",
        "EMPNO=110; DEPTNO=40; NAME=John",
        "EMPNO=120; DEPTNO=20; NAME=Wilma",
        "EMPNO=130; DEPTNO=40; NAME=Alice");
  }

  /** Reads the DEPTS table. */
  @Test public void testSalesDepts() throws SQLException {
    final String sql = "select * from sales.depts";
    checkSql("sales", sql,
        "DEPTNO=10; NAME=Sales",
        "DEPTNO=20; NAME=Marketing",
        "DEPTNO=30; NAME=Accounts");
  }

}

// End SqlTest.java
