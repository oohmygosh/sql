/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import org.opensearch.sql.job.QueryJobState;

/** Public task snapshot. Counters describe output flushed to the export file. */
public record ExportTaskStatus(
    String id,
    QueryJobState state,
    long exportedRows,
    long fileSizeBytes,
    long submittedAtMillis,
    Long completedAtMillis,
    String error) {}
