/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

/** Display name (including aliases) and the language's public type name. */
public record QueryColumn(String name, String type) {}
