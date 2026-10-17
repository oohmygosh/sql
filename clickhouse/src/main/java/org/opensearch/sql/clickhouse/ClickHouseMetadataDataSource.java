/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** JDBC reports unbounded String as size zero; Calcite otherwise treats it as VARCHAR(0). */
final class ClickHouseMetadataDataSource implements DataSource {
  private final DataSource delegate;

  ClickHouseMetadataDataSource(DataSource delegate) {
    this.delegate = delegate;
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private Connection wrap(Connection connection) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoke(connection, method, args);
              if (!method.getName().equals("getMetaData")) return result;
              DatabaseMetaData metadata = (DatabaseMetaData) result;
              return Proxy.newProxyInstance(
                  DatabaseMetaData.class.getClassLoader(),
                  new Class<?>[] {DatabaseMetaData.class},
                  (p, m, a) -> {
                    Object metadataResult = invoke(metadata, m, a);
                    if (!m.getName().equals("getColumns")) return metadataResult;
                    ResultSet columns = (ResultSet) metadataResult;
                    return Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class<?>[] {ResultSet.class},
                        (p2, m2, a2) -> {
                          Object value = invoke(columns, m2, a2);
                          if (m2.getName().equals("getInt")
                              && a2 != null
                              && (Integer.valueOf(7).equals(a2[0]) || "COLUMN_SIZE".equals(a2[0]))
                              && Integer.valueOf(0).equals(value)) {
                            int type = columns.getInt("DATA_TYPE");
                            if (type == Types.VARCHAR || type == Types.LONGVARCHAR) return -1;
                          }
                          return value;
                        });
                  });
            });
  }

  @Override
  public Connection getConnection() throws SQLException {
    return wrap(delegate.getConnection());
  }

  @Override
  public Connection getConnection(String user, String password) throws SQLException {
    return wrap(delegate.getConnection(user, password));
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter writer) throws SQLException {
    delegate.setLogWriter(writer);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
  }

  @Override
  public boolean isWrapperFor(Class<?> type) throws SQLException {
    return type.isInstance(this) || delegate.isWrapperFor(type);
  }
}
