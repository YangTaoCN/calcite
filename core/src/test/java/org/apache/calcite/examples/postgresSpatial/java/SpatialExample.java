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
package org.apache.calcite.examples.postgresSpatial.java;

import org.apache.calcite.adapter.java.ReflectiveSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.rel.rules.SpatialRules;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.Schema;

import org.apache.calcite.test.catalog.MockCatalogReaderExtended;

import org.apache.commons.dbcp2.BasicDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;


/**
 * Example of using Calcite via JDBC.
 *
 * <p>Schema is specified programmatically.</p>
 */
public class SpatialExample {
  public static void main(String[] args) throws Exception {
    new SpatialExample().run();
  }

  public void run() throws ClassNotFoundException, SQLException {
    String url="jdbc:postgresql://127.0.0.1/postgres";
    String user="taoyang";
    String password = "passward";

    Connection connection =
        DriverManager.getConnection("jdbc:calcite:fun=spatial");
    CalciteConnection calciteConnection =
        connection.unwrap(CalciteConnection.class);
    SchemaPlus rootSchema = calciteConnection.getRootSchema();

    // Schema from postgres
    Class.forName("org.postgresql.Driver");
    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl(url);
    dataSource.setUsername(user);
    dataSource.setPassword(password);
    Schema schema = JdbcSchema.create(rootSchema, "PB", dataSource, null, "public");
    rootSchema.add("PB", schema);

    Statement statement = calciteConnection.createStatement();

    String sql = "select * \n"
        + "from pb.\"geonames_small\" as r\n"
        + "where ST_DWithin(ST_Point(10.0, 20.0),\n"
        + "ST_Point(r.\"longitude\", r.\"latitude\"),"
        + " 10)";



    ResultSet resultSet = statement.executeQuery(sql);

    final StringBuilder buf = new StringBuilder();
    while (resultSet.next()) {
      int n = resultSet.getMetaData().getColumnCount();
      for (int i = 1; i <= n; i++) {
        buf.append(i > 1 ? "; " : "")
            .append(resultSet.getMetaData().getColumnLabel(i))
            .append("=")
            .append(resultSet.getObject(i));
      }
      System.out.println(buf.toString());
      buf.setLength(0);
    }
    resultSet.close();
    statement.close();
    connection.close();
  }
}
