/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.clickhouse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.calcite.DataContext;
import org.apache.calcite.DataContexts;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.sql.api.UnifiedQueryService;
import org.opensearch.sql.backend.MachineLearningProvider;
import org.opensearch.sql.executor.QueryType;

class ClickHouseMlProviderTest {
  private static final RelDataType INPUT_TYPE =
      new JavaTypeFactoryImpl()
          .builder()
          .add("note", SqlTypeName.VARCHAR)
          .add("x", SqlTypeName.DOUBLE)
          .add("label", SqlTypeName.DOUBLE)
          .add("extra", SqlTypeName.ANY)
          .build();

  @Test
  void backendExposesNativeProviderAndPlanningDoesNotOpenJdbc() {
    DataSource dataSource = mock(DataSource.class);
    MachineLearningProvider provider =
        new ClickHouseQueryBackend("ch", dataSource, "default").machineLearning();
    var operation = provider.plan(arguments("prediction_field", "estimate"), INPUT_TYPE);
    assertEquals(
        List.of("note", "x", "label", "extra", "estimate"), operation.outputType().getFieldNames());
    assertEquals(
        SqlTypeName.DOUBLE,
        operation.outputType().getFieldList().get(4).getType().getSqlTypeName());
    verifyNoInteractions(dataSource);
  }

  @Test
  void lazyLinearFitBindsOnlyNumericDataAndPreservesOriginalRows() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {{0, 7.0}, {1, 11.0}});
    var extra = Map.of("nested", List.of("keep", 7));
    Object[] first = {"quote'); DROP TABLE harmless; --", 3.0, 7.0, extra};
    Object[] second = {"second", 5.0, 11.0, null};
    TrackedInput input = new TrackedInput(List.of(first, second));
    var operation = fixture.provider.plan(arguments(), INPUT_TYPE);
    Enumerable<Object[]> output = operation.execute(input, DataContexts.EMPTY);
    assertEquals(0, input.opened.get());
    verifyNoInteractions(fixture.source);
    try (var cursor = output.enumerator()) {
      assertEquals(0, input.opened.get());
      assertTrue(cursor.moveNext());
      assertArrayEquals(new Object[] {first[0], 3.0, 7.0, extra, 7.0}, cursor.current());
      assertSame(extra, cursor.current()[3]);
      assertTrue(cursor.moveNext());
      assertArrayEquals(new Object[] {"second", 5.0, 11.0, null, 11.0}, cursor.current());
      assertFalse(cursor.moveNext());
    }
    assertEquals(4, first.length);
    assertEquals(1, input.closed.get());
    assertEquals(
        List.of(0, 7.0, 3.0, 1, 11.0, 5.0, 0.00001, 0.1, 1, "Adam"),
        new ArrayList<>(fixture.bound.values()));
    String sql = fixture.sql();
    assertTrue(sql.contains("values('_ml_row UInt32, _ml_target Float64, _ml_f0 Float64'"), sql);
    assertTrue(
        sql.contains("stochasticLinearRegressionState(?, ?, ?, ?)(_ml_target, _ml_f0)"), sql);
    assertTrue(sql.contains("evalMLMethod(_ml_model, _ml_f0)"), sql);
    assertTrue(sql.contains("ORDER BY _ml_row"), sql);
    assertFalse(sql.contains("harmless"), sql);
    verify(fixture.statement).setQueryTimeout(30);
    verify(fixture.statement).executeQuery();
    fixture.verifyClosed();
  }

  @Test
  void pplPipelineRoutesParsedOptionsToNativeSqlAndFiltersPredictions() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {{0, 7.0}, {1, 11.0}});
    var backend = new ClickHouseQueryBackend("ch", fixture.source, "default");
    var service = new UnifiedQueryService(List.of(backend), "ch");
    var response =
        service.execute(
            QueryType.PPL,
            "makeresults count=2 | eval x=3.0, label=7.0 | fields x,label | ml"
                + " action='trainandpredict' algorithm='linear_regression' target='label'"
                + " features='x' learning_rate=0.05 prediction_field='estimate'"
                + " | where estimate > 8 | fields x,estimate");
    assertEquals(1, response.getResults().size());
    assertEquals(11.0, response.getResults().getFirst().tupleValue().get("estimate").doubleValue());
    assertEquals(
        List.of(0, 7.0, 3.0, 1, 7.0, 3.0, 0.05, 0.1, 1, "Adam"),
        new ArrayList<>(fixture.bound.values()));
    fixture.verifyClosed();
  }

  @Test
  void logisticMapsBinaryLabelsAndReturnsProbability() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {{0, 0.2}, {1, 0.8}});
    var operation =
        fixture.provider.plan(
            arguments(
                "algorithm",
                "logistic_regression",
                "learning_rate",
                0.2,
                "l2",
                0,
                "batch_size",
                1,
                "optimizer",
                "SGD"),
            INPUT_TYPE);
    List<Object[]> result =
        operation.execute(input(row(-2, 0), row(2, 1)), DataContexts.EMPTY).toList();
    assertEquals(0.2, result.get(0)[4]);
    assertEquals(0.8, result.get(1)[4]);
    assertEquals(0.0, result.get(0)[2]);
    assertEquals(1.0, result.get(1)[2]);
    assertEquals(
        List.of(0, -1.0, -2.0, 1, 1.0, 2.0, 0.2, 0.0, 1, "SGD"),
        new ArrayList<>(fixture.bound.values()));
    assertTrue(fixture.sql().contains("stochasticLogisticRegressionState"));
    fixture.verifyClosed();
  }

  @Test
  void rejectsIncompleteMiniBatchesBeforeOpeningJdbc() throws Exception {
    for (int count : List.of(1, 16)) {
      Fixture fixture = new Fixture(new Object[0][]);
      var input =
          new TrackedInput(Collections.nCopies(count, new Object[] {"note", 1.0, 10.0, null}));
      var operation = fixture.provider.plan(arguments("batch_size", 15), INPUT_TYPE);
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> operation.execute(input, DataContexts.EMPTY).toList());
      assertTrue(failure.getMessage().contains("batch_size must divide the input row count"));
      assertEquals(1, input.closed.get());
      verifyNoInteractions(fixture.source);
    }
  }

  @Test
  void completeMiniBatchesUseTheRequestedNativeBatchSize() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {{0, 7.0}, {1, 11.0}});
    var operation = fixture.provider.plan(arguments("batch_size", 2), INPUT_TYPE);
    var input =
        new TrackedInput(
            List.of(new Object[] {"one", 3.0, 7.0, null}, new Object[] {"two", 5.0, 11.0, null}));
    assertEquals(2, operation.execute(input, DataContexts.EMPTY).toList().size());
    assertEquals(2, fixture.bound.get(9));
    fixture.verifyClosed();
  }

  @Test
  void rejectsInvalidOptionsAndFieldsWithoutReadingInput() {
    var source = mock(DataSource.class);
    var provider = new ClickHouseMlProvider(source);
    Object[][] invalid = {
      {"action", "predict"},
      {"algorithm", "kmeans"},
      {"target", "absent"},
      {"target", "note"},
      {"target", null},
      {"features", ""},
      {"features", "x,"},
      {"features", "x,x"},
      {"features", "x, x"},
      {"features", "label"},
      {"features", "absent"},
      {"features", "note"},
      {"features", "extra"},
      {"prediction_field", "LABEL"},
      {"prediction_field", " "},
      {"prediction_field", null},
      {"max_rows", 0},
      {"max_rows", 10001},
      {"max_rows", 1.5},
      {"max_rows", null},
      {"learning_rate", 0},
      {"learning_rate", -1},
      {"learning_rate", "0.1"},
      {"learning_rate", Double.NaN},
      {"learning_rate", Double.POSITIVE_INFINITY},
      {"learning_rate", null},
      {"l2", -0.1},
      {"l2", Double.NaN},
      {"batch_size", 0},
      {"batch_size", 10001},
      {"optimizer", "invalid"},
      {"unsupported", 1}
    };
    for (Object[] option : invalid) {
      assertThrows(
          IllegalArgumentException.class,
          () -> provider.plan(arguments((String) option[0], option[1]), INPUT_TYPE),
          java.util.Arrays.toString(option));
    }
    verifyNoInteractions(source);
  }

  @Test
  void rejectsNullNonnumericAndNonfiniteTrainingValuesAndClosesInput() {
    for (Object value : new Object[] {null, "2", Double.NaN, Double.NEGATIVE_INFINITY}) {
      for (int column : List.of(1, 2)) {
        DataSource source = mock(DataSource.class);
        var operation = new ClickHouseMlProvider(source).plan(arguments(), INPUT_TYPE);
        Object[] row = row(2, 4);
        row[column] = value;
        TrackedInput input = new TrackedInput(Collections.singletonList(row));
        assertThrows(
            IllegalArgumentException.class,
            () -> operation.execute(input, DataContexts.EMPTY).toList());
        assertEquals(1, input.closed.get());
        verifyNoInteractions(source);
      }
    }
  }

  @Test
  void rejectsNonBinaryLogisticTarget() {
    for (Object value :
        new Object[] {-1.0, 0.5, 2.0, new java.math.BigDecimal("1.0000000000000000001")}) {
      DataSource source = mock(DataSource.class);
      var operation =
          new ClickHouseMlProvider(source)
              .plan(arguments("algorithm", "logistic_regression"), INPUT_TYPE);
      Object[] row = row(2, 0);
      row[2] = value;
      TrackedInput input = new TrackedInput(Collections.singletonList(row));
      assertThrows(
          IllegalArgumentException.class,
          () -> operation.execute(input, DataContexts.EMPTY).toList());
      assertEquals(1, input.closed.get());
      verifyNoInteractions(source);
    }
  }

  @Test
  void rejectsRowCellAndSqlLimitsBeforeOpeningConnection() {
    DataSource source = mock(DataSource.class);
    var provider = new ClickHouseMlProvider(source);
    TrackedInput tooManyRows = new TrackedInput(List.of(row(1, 2), row(2, 3), row(3, 4)));
    var rowLimit = provider.plan(arguments("max_rows", 2), INPUT_TYPE);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> rowLimit.execute(tooManyRows, DataContexts.EMPTY).toList())
            .getMessage()
            .contains("max_rows=2"));
    assertEquals(1, tooManyRows.closed.get());

    var builder = new JavaTypeFactoryImpl().builder().addAll(INPUT_TYPE.getFieldList());
    for (int i = 4; i < 101; i++) {
      builder.add("unused_" + i, SqlTypeName.DOUBLE);
    }
    Object[] wide = new Object[101];
    wide[1] = 1.0;
    wide[2] = 2.0;
    TrackedInput tooManyCells = new TrackedInput(Collections.nCopies(1000, wide));
    var cellLimit = provider.plan(arguments("max_rows", 10000), builder.build());
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> cellLimit.execute(tooManyCells, DataContexts.EMPTY).toList())
            .getMessage()
            .contains("cells"));
    assertEquals(1, tooManyCells.closed.get());

    TrackedInput tooMuchSql =
        new TrackedInput(Collections.nCopies(3000, row(Double.MAX_VALUE, Double.MAX_VALUE)));
    var sqlLimit = provider.plan(arguments("max_rows", 10000), INPUT_TYPE);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> sqlLimit.execute(tooMuchSql, DataContexts.EMPTY).toList())
            .getMessage()
            .contains("SQL limit"));
    assertEquals(1, tooMuchSql.closed.get());
    verifyNoInteractions(source);
  }

  @Test
  void emptyInputAndCloseBeforeFirstReadDoNotQueryClickHouse() {
    DataSource source = mock(DataSource.class);
    var operation = new ClickHouseMlProvider(source).plan(arguments(), INPUT_TYPE);
    TrackedInput empty = new TrackedInput(List.of());
    assertTrue(operation.execute(empty, DataContexts.EMPTY).toList().isEmpty());
    assertEquals(1, empty.closed.get());
    TrackedInput unread = new TrackedInput(Collections.singletonList(row(1, 2)));
    var cursor = operation.execute(unread, DataContexts.EMPTY).enumerator();
    cursor.close();
    assertFalse(cursor.moveNext());
    assertEquals(0, unread.opened.get());
    verifyNoInteractions(source);
  }

  @Test
  void preservesRowsWhenUpstreamReusesItsArray() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {{0, 2.0}, {1, 4.0}});
    Object[] reused = row(0, 0);
    Enumerable<Object[]> input =
        new AbstractEnumerable<>() {
          @Override
          public Enumerator<Object[]> enumerator() {
            return new Enumerator<>() {
              int index;

              public Object[] current() {
                return reused;
              }

              public boolean moveNext() {
                reused[1] = (double) ++index;
                reused[2] = (double) (index * 2);
                return index <= 2;
              }

              public void reset() {
                throw new UnsupportedOperationException();
              }

              public void close() {}
            };
          }
        };
    List<Object[]> rows =
        fixture.provider.plan(arguments(), INPUT_TYPE).execute(input, DataContexts.EMPTY).toList();
    assertEquals(1.0, rows.get(0)[1]);
    assertEquals(2.0, rows.get(1)[1]);
    fixture.verifyClosed();
  }

  @Test
  void rejectsMissingExtraDuplicateOutOfOrderAndNullOrdinals() throws Exception {
    List<Object[][]> invalid =
        List.of(
            new Object[][] {},
            new Object[][] {{0, 2.0}},
            new Object[][] {{0, 2.0}, {1, 4.0}, {2, 6.0}},
            new Object[][] {{0, 2.0}, {0, 4.0}},
            new Object[][] {{1, 4.0}, {0, 2.0}},
            new Object[][] {{null, 2.0}, {1, 4.0}});
    for (Object[][] returned : invalid) {
      Fixture fixture = new Fixture(returned);
      var operation = fixture.provider.plan(arguments(), INPUT_TYPE);
      assertThrows(
          IllegalStateException.class,
          () -> operation.execute(input(row(1, 2), row(2, 4)), DataContexts.EMPTY).toList());
      fixture.verifyClosed();
    }
  }

  @Test
  void rejectsNullNonfiniteAndOutOfRangePredictions() throws Exception {
    for (Object prediction : new Object[] {null, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
      Fixture fixture = new Fixture(new Object[][] {{0, prediction}});
      var operation =
          fixture.provider.plan(arguments("algorithm", "logistic_regression"), INPUT_TYPE);
      assertThrows(
          IllegalStateException.class,
          () -> operation.execute(input(row(1, 1)), DataContexts.EMPTY).toList());
      fixture.verifyClosed();
    }
  }

  @Test
  void closesInputAndJdbcAfterSqlFailure() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {});
    when(fixture.statement.executeQuery()).thenThrow(new SQLException("native failure"));
    TrackedInput input = new TrackedInput(Collections.singletonList(row(1, 2)));
    var operation = fixture.provider.plan(arguments(), INPUT_TYPE);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> operation.execute(input, DataContexts.EMPTY).toList())
            .getMessage()
            .contains("native failure"));
    assertEquals(1, input.closed.get());
    verify(fixture.statement).close();
    verify(fixture.connection).close();
  }

  @Test
  void cancellationBeforeInputReadDoesNotOpenResources() {
    DataSource source = mock(DataSource.class);
    var operation = new ClickHouseMlProvider(source).plan(arguments(), INPUT_TYPE);
    TrackedInput input = new TrackedInput(Collections.singletonList(row(1, 2)));
    DataContext context = cancelledContext(new AtomicBoolean(true));
    assertThrows(CancellationException.class, () -> operation.execute(input, context).toList());
    assertEquals(0, input.opened.get());
    verifyNoInteractions(source);
  }

  @Test
  void cancellationDuringInputReadClosesUpstream() {
    DataSource source = mock(DataSource.class);
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicBoolean closed = new AtomicBoolean();
    Enumerable<Object[]> input =
        new AbstractEnumerable<>() {
          @Override
          public Enumerator<Object[]> enumerator() {
            return new Enumerator<>() {
              public Object[] current() {
                return row(1, 2);
              }

              public boolean moveNext() {
                cancelled.set(true);
                return true;
              }

              public void reset() {
                throw new UnsupportedOperationException();
              }

              public void close() {
                closed.set(true);
              }
            };
          }
        };
    var operation = new ClickHouseMlProvider(source).plan(arguments(), INPUT_TYPE);
    assertThrows(
        CancellationException.class,
        () -> operation.execute(input, cancelledContext(cancelled)).toList());
    assertTrue(closed.get());
    verifyNoInteractions(source);
  }

  @Test
  void cancellationDuringResultReadClosesAllResources() throws Exception {
    Fixture fixture = new Fixture(new Object[][] {{0, 2.0}});
    AtomicBoolean cancelled = new AtomicBoolean();
    when(fixture.result.next())
        .thenAnswer(
            invocation -> {
              cancelled.set(true);
              return true;
            });
    TrackedInput input = new TrackedInput(Collections.singletonList(row(1, 2)));
    var operation = fixture.provider.plan(arguments(), INPUT_TYPE);
    assertThrows(
        CancellationException.class,
        () -> operation.execute(input, cancelledContext(cancelled)).toList());
    assertEquals(1, input.closed.get());
    fixture.verifyClosed();
  }

  private static DataContext cancelledContext(AtomicBoolean flag) {
    DataContext context = mock(DataContext.class);
    when(context.get(DataContext.Variable.CANCEL_FLAG.camelName)).thenReturn(flag);
    return context;
  }

  private static Object[] row(double feature, double target) {
    return new Object[] {"keep", feature, target, null};
  }

  private static Enumerable<Object[]> input(Object[]... rows) {
    return Linq4j.asEnumerable(rows);
  }

  private static Map<String, Object> arguments(Object... overrides) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("action", "trainandpredict");
    arguments.put("algorithm", "linear_regression");
    arguments.put("target", "label");
    arguments.put("features", "x");
    for (int i = 0; i < overrides.length; i += 2) {
      arguments.put((String) overrides[i], overrides[i + 1]);
    }
    return arguments;
  }

  private static final class TrackedInput extends AbstractEnumerable<Object[]> {
    private final List<Object[]> rows;
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    private TrackedInput(List<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    public Enumerator<Object[]> enumerator() {
      opened.incrementAndGet();
      return new Enumerator<>() {
        int index = -1;

        public Object[] current() {
          return rows.get(index);
        }

        public boolean moveNext() {
          return ++index < rows.size();
        }

        public void reset() {
          throw new UnsupportedOperationException();
        }

        public void close() {
          closed.incrementAndGet();
        }
      };
    }
  }

  private static final class Fixture {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private final Map<Integer, Object> bound = new LinkedHashMap<>();
    private final MachineLearningProvider provider = new ClickHouseMlProvider(source);

    private Fixture(Object[][] predictions) throws Exception {
      when(source.getConnection()).thenReturn(connection);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.executeQuery()).thenReturn(result);
      AtomicInteger index = new AtomicInteger(-1);
      AtomicBoolean wasNull = new AtomicBoolean();
      when(result.next()).thenAnswer(invocation -> index.incrementAndGet() < predictions.length);
      when(result.getInt(1))
          .thenAnswer(
              invocation -> {
                Object ordinal = predictions[index.get()][0];
                wasNull.set(ordinal == null);
                return ordinal == null ? 0 : ((Number) ordinal).intValue();
              });
      when(result.getDouble(2))
          .thenAnswer(
              invocation -> {
                Object value = predictions[index.get()][1];
                wasNull.set(value == null);
                return value == null ? 0 : ((Number) value).doubleValue();
              });
      when(result.wasNull()).thenAnswer(invocation -> wasNull.get());
      org.mockito.stubbing.Answer<Void> bind =
          invocation -> {
            bound.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
          };
      doAnswer(bind).when(statement).setInt(anyInt(), anyInt());
      doAnswer(bind).when(statement).setDouble(anyInt(), anyDouble());
      doAnswer(bind).when(statement).setString(anyInt(), anyString());
    }

    private String sql() throws Exception {
      ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
      verify(connection).prepareStatement(sql.capture());
      return sql.getValue();
    }

    private void verifyClosed() throws Exception {
      verify(result).close();
      verify(statement).close();
      verify(connection).close();
    }
  }
}
