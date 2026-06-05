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
import java.util.ArrayList;
import java.util.List;

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

  /** One result item: its serialized {@code value} and XDM {@code type} (e.g. {@code xs:integer}). */
  public record Item(String value, String type) {
  }

  /**
   * The serialized output, the individual result items (in order), the total item count, and whether
   * the output was truncated. {@code output} is the items' values joined one per line — the same text
   * shown in the results editor; {@code items} lets callers build a navigable per-result list.
   */
  public record QueryResult(String output, List<Item> items, int totalItems, boolean truncated) {
  }

  /**
   * Executes {@code query} (optionally with a {@code moduleLoadPath} for import resolution) via the
   * cursor API, serializing up to {@link #MAX_ITEMS} result items as one {@code value} per line. The
   * server-side cursor is always released.
   */
  public static QueryResult execute(ExistClient client, String query, String moduleLoadPath)
      throws IOException, InterruptedException {
    return execute(client, query, moduleLoadPath, null);
  }

  /**
   * As {@link #execute(ExistClient, String, String)}, but supplies {@code contextItem} (a serialized
   * node) as the evaluation context item so context-dependent expressions evaluate against the
   * document being queried. A null/blank {@code contextItem} runs with no context item.
   */
  public static QueryResult execute(
      ExistClient client, String query, String moduleLoadPath, String contextItem)
      throws IOException, InterruptedException {
    ExistClient.QueryHandle handle = client.runQuery(query, moduleLoadPath, contextItem);
    int total = handle.items();
    if (handle.cursor() == null || total == 0) {
      return new QueryResult("", List.of(), total, false);
    }
    int count = Math.min(total, MAX_ITEMS);
    try {
      String body = client.fetchResultsRaw(handle.cursor(), 1, count, "adaptive");
      List<Item> items = parseItems(body);
      String output = serialize(items);
      return new QueryResult(output, items, total, total > MAX_ITEMS);
    } finally {
      client.closeCursor(handle.cursor());
    }
  }

  /** Parses the result array into {@link Item}s, preserving each item's value and type. */
  private static List<Item> parseItems(String body) {
    JSONArray array = new JSONArray(body);
    List<Item> items = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.getJSONObject(i);
      items.add(new Item(item.optString("value", item.toString()), item.optString("type", "")));
    }
    return items;
  }

  /** Joins the items' {@code value} fields, one item per line — the results-editor text. */
  private static String serialize(List<Item> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(items.get(i).value());
    }
    return sb.toString();
  }

  /** True when the serialized output looks like an XML document (so it can open in an XML editor). */
  public static boolean looksLikeXml(String output) {
    return output != null && output.stripLeading().startsWith("<");
  }
}
