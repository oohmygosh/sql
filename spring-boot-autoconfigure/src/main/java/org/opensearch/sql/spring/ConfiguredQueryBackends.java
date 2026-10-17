/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opensearch.sql.backend.QueryBackend;

/** Owns only connections created from configuration, never application-supplied backend beans. */
public final class ConfiguredQueryBackends implements AutoCloseable {
  private final List<QueryBackend> owned = new ArrayList<>();
  private final List<QueryBackend> all;

  ConfiguredQueryBackends(
      UnifiedQueryProperties properties,
      List<QueryBackendFactory> factories,
      List<QueryBackend> supplied)
      throws Exception {
    Map<String, QueryBackendFactory> types = new LinkedHashMap<>();
    for (var factory : factories) {
      if (types.putIfAbsent(factory.type(), factory) != null)
        throw new IllegalArgumentException("Duplicate backend factory: " + factory.type());
    }
    Map<String, QueryBackend> sources = new LinkedHashMap<>();
    for (var backend : supplied) {
      if (sources.putIfAbsent(backend.name(), backend) != null)
        throw new IllegalArgumentException("Duplicate data source: " + backend.name());
    }
    try {
      for (var entry : properties.getDataSources().entrySet()) {
        if (sources.containsKey(entry.getKey())) continue;
        var config = entry.getValue();
        var factory = types.get(config.getType());
        if (factory == null)
          throw new IllegalArgumentException(
              "No backend factory for "
                  + config.getType()
                  + "; add its backend dependency or a factory bean");
        QueryBackend backend = factory.create(entry.getKey(), config);
        owned.add(backend);
        sources.put(entry.getKey(), backend);
      }
      all = List.copyOf(sources.values());
    } catch (Exception e) {
      try {
        close();
      } catch (Exception cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  public List<QueryBackend> all() {
    return all;
  }

  @Override
  public void close() throws Exception {
    Exception failure = null;
    for (int i = owned.size() - 1; i >= 0; i--) {
      try {
        owned.get(i).close();
      } catch (Exception e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    owned.clear();
    if (failure != null) throw failure;
  }
}
