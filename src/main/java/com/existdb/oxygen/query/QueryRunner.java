/*
 * eXist-db Oxygen Plugin
 * Copyright (C) 2026 The eXist-db Authors
 *
 * info@exist-db.org
 * https://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package com.existdb.oxygen.query;

import com.existdb.oxygen.client.ExistClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Runs an XQuery against eXist and serializes the result sequence to text. Shared by the
 * <b>Run Current Editor</b> action (which opens the output in a new editor) and the eXist-db
 * transformation engine (which writes it to a scenario's output), so execution and serialization
 * live in one place.
 */
public final class QueryRunner {

  /** Upper bound on items fetched in one run; the result reports whether more were available. */
  public static final int MAX_ITEMS = 1000;

  private QueryRunner() {
  }

  /** The serialized output plus the total item count and whether the output was truncated. */
  public record QueryResult(String output, int totalItems, boolean truncated) {
  }

  /**
   * Executes {@code query} (optionally with a {@code moduleLoadPath} for import resolution) via the
   * cursor API, serializing up to {@link #MAX_ITEMS} result items as one {@code value} per line. The
   * server-side cursor is always released.
   */
  public static QueryResult execute(ExistClient client, String query, String moduleLoadPath)
      throws IOException, InterruptedException {
    ExistClient.QueryHandle handle = client.runQuery(query, moduleLoadPath);
    int total = handle.items();
    if (handle.cursor() == null || total == 0) {
      return new QueryResult("", total, false);
    }
    int count = Math.min(total, MAX_ITEMS);
    try {
      String body = client.fetchResultsRaw(handle.cursor(), 1, count, "adaptive");
      return new QueryResult(serialize(body), total, total > MAX_ITEMS);
    } finally {
      client.closeCursor(handle.cursor());
    }
  }

  /** Joins the result array's {@code value} fields, one item per line. */
  private static String serialize(String body) {
    JSONArray items = new JSONArray(body);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(item.optString("value", item.toString()));
    }
    return sb.toString();
  }

  /** True when the serialized output looks like an XML document (so it can open in an XML editor). */
  public static boolean looksLikeXml(String output) {
    return output != null && output.stripLeading().startsWith("<");
  }
}
