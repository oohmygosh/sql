/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.opensearch.sql.executor.Warning;

/** Bounded interactive result. Both total and size count returned rows, not all source matches. */
public record QueryResponse(
    List<QueryColumn> schema,
    List<Object[]> datarows,
    long total,
    long size,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Warning> warnings) {}
