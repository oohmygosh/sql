/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.example.model;

import java.io.InputStream;

/** Open download handle. Its caller owns and closes the stream. */
public record ExportDownload(InputStream stream, long size) {}
