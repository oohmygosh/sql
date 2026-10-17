/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spring;

import org.opensearch.sql.backend.QueryBackend;

/** Applications add a backend type by providing another factory bean. */
public interface QueryBackendFactory {
  String type();

  QueryBackend create(String name, UnifiedQueryProperties.DataSource configuration)
      throws Exception;
}
