/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates registered languages without making their syntax or scope part of the UI protocol.
 */
public final class QueryCompletionService {
  private static final int MAX_ITEMS = 100;
  private final CompletionMetadata metadata;
  private final Map<String, LanguageCompletionProvider> providers;

  public QueryCompletionService(
      CompletionMetadata metadata, List<LanguageCompletionProvider> providers) {
    this.metadata = metadata;
    Map<String, LanguageCompletionProvider> registered = new LinkedHashMap<>();
    for (var provider : providers) {
      if (registered.putIfAbsent(provider.language(), provider) != null)
        throw new IllegalArgumentException("Duplicate completion language: " + provider.language());
    }
    this.providers = java.util.Collections.unmodifiableMap(registered);
  }

  public List<String> languages() {
    return List.copyOf(providers.keySet());
  }

  public CompletionResponse complete(CompletionRequest request) {
    var provider = providers.get(request.language());
    if (provider == null)
      throw new IllegalArgumentException("Completion is not available for " + request.language());
    String source = metadata.resolveSource(request.source());
    var snapshot =
        new CompletionRequest(
            request.language(),
            source,
            request.text(),
            request.caret(),
            request.documentVersion(),
            request.trigger());
    var response = provider.complete(snapshot, metadata);
    for (var item : response.items()) {
      var edit = item.textEdit();
      if (edit == null
          || edit.from() < 0
          || edit.from() > request.caret()
          || edit.to() < request.caret()
          || edit.to() > request.text().length()
          || edit.newText() == null
          || item.caretOffset() != null
              && (item.caretOffset() < 0 || item.caretOffset() > edit.newText().length()))
        throw new IllegalStateException("Language provider returned an invalid completion edit");
    }
    // Preserve the adapter's contextual syntax alongside a large catalog, while retaining order.
    int referenceBudget =
        Math.max(
            0,
            MAX_ITEMS - (int) response.items().stream().filter(item -> !reference(item)).count());
    int references = 0;
    List<CompletionResponse.Item> visible = new ArrayList<>();
    for (var item : response.items()) {
      if (reference(item) && references++ >= referenceBudget) continue;
      visible.add(item);
      if (visible.size() == MAX_ITEMS) break;
    }
    return new CompletionResponse(
        request.documentVersion(),
        List.copyOf(visible),
        response.incomplete() || visible.size() < response.items().size(),
        response.message());
  }

  private static boolean reference(CompletionResponse.Item item) {
    return "field".equals(item.kind()) || "table".equals(item.kind());
  }
}
