/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.List;

/**
 * Catalog facts shared by language providers; identifiers here are semantic, never quoted SQL/PPL.
 */
public interface CompletionMetadata {
  String resolveSource(String source);

  /** Optional execution capabilities of the selected backend, independent of query language. */
  default java.util.Set<String> capabilities(String source) {
    return java.util.Set.of();
  }

  /** Names registered by a backend extension whose grammar admits dynamic arguments. */
  default List<String> parameterNames(String source, String capability) {
    return List.of();
  }

  List<Relation> tables(String source, List<String> namespace, String prefix, int limit);

  List<Field> fields(String source, RelationRef relation);

  record Relation(List<String> namespace, String name, String kind) {
    public Relation {
      namespace = List.copyOf(namespace);
    }
  }

  record RelationRef(List<String> namespace, String name) {
    public RelationRef {
      namespace = List.copyOf(namespace);
    }
  }

  /** Path components distinguish a literal dotted name from access through a structured column. */
  record Field(String name, String type, List<String> path) {
    public Field {
      path = List.copyOf(path);
    }

    public Field(String name, String type) {
      this(name, type, List.of(name));
    }
  }

  /** A failed catalog lookup is different from a successfully loaded empty schema. */
  class MetadataException extends RuntimeException {
    public MetadataException(String message) {
      super(message);
    }

    public MetadataException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
