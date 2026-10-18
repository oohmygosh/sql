/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

/**
 * A language owns incomplete syntax, scope rules, identifier quoting and contextual suggestions.
 */
public interface LanguageCompletionProvider {
  String language();

  CompletionResponse complete(CompletionRequest request, CompletionMetadata metadata);
}
