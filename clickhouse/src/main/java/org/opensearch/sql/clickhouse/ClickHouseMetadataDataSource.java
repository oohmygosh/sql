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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.calcite.runtime.SqlFunctions;

/** Adapts ClickHouse JDBC metadata and UTC timestamp values to Calcite's type conventions. */
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
              if (result instanceof Statement statement) return wrap(statement);
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
                          if (m2.getName().equals("getInt") && a2 != null) {
                            String typeName = columns.getString("TYPE_NAME");
                            if ("UInt64".equals(typeName) || "Nullable(UInt64)".equals(typeName)) {
                              // BIGINT cannot hold UInt64; OTHER becomes ANY and breaks numeric
                              // analysis. DECIMAL(20,0) keeps every bit, including values > 2^63-1.
                              if (Integer.valueOf(5).equals(a2[0]) || "DATA_TYPE".equals(a2[0]))
                                return Types.DECIMAL;
                              if (Integer.valueOf(7).equals(a2[0]) || "COLUMN_SIZE".equals(a2[0]))
                                return 20;
                              if (Integer.valueOf(9).equals(a2[0])
                                  || "DECIMAL_DIGITS".equals(a2[0])) return 0;
                            }
                          }
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

  private Statement wrap(Statement statement) {
    Class<? extends Statement> api =
        statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement)
        Proxy.newProxyInstance(
            api.getClassLoader(),
            new Class<?>[] {api},
            (proxy, method, args) -> {
              Object result = invoke(statement, method, args);
              return result instanceof ResultSet rows ? wrap(rows) : result;
            });
  }

  private ResultSet wrap(ResultSet rows) {
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              String getter = method.getName();
              if ((getter.equals("getLong") || getter.equals("getInt")) && args.length == 1) {
                int index =
                    args[0] instanceof Integer number ? number : rows.findColumn((String) args[0]);
                int type = rows.getMetaData().getColumnType(index);
                if (getter.equals("getLong")
                    && (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE)) {
                  // getTimestamp() rebuilds column-local calendar fields and loses the offset.
                  // The typed getter retains the instant, even across a DST overlap. DIRECT
                  // tells Calcite to consume these epoch milliseconds without another shift.
                  OffsetDateTime value = rows.getObject(index, OffsetDateTime.class);
                  return value == null ? 0L : value.toInstant().toEpochMilli();
                }
                if (getter.equals("getInt") && type == Types.DATE) {
                  LocalDate value = rows.getObject(index, LocalDate.class);
                  return value == null ? 0 : Math.toIntExact(value.toEpochDay());
                }
                if (getter.equals("getInt") && type == Types.TIME) {
                  LocalTime value = rows.getObject(index, LocalTime.class);
                  return value == null ? 0 : (int) (value.toNanoOfDay() / 1_000_000);
                }
              }
              Object result = invoke(rows, method, args);
              // DIRECT also bypasses JdbcToEnumerableConverter's usual ARRAY-to-List conversion.
              return getter.equals("getObject")
                      && args.length == 1
                      && result instanceof java.sql.Array array
                  ? SqlFunctions.arrayToList(array)
                  : result;
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
