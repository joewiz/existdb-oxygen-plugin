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
package com.existdb.oxygen.client;

import org.json.JSONObject;

/**
 * A parsed XQuery compile/eval error from existdb-openapi's {@code /api/query} error envelope.
 * Since eXist-db/existdb-openapi#71 the envelope is {@code code}, {@code message} (the clean human
 * reason), 1-based user-relative {@code line}/{@code column}, and {@code raw} (the original text).
 * Used to show an XQuery error as readable prose instead of the raw status line + JSON blob.
 *
 * <p>{@code line}/{@code column} are 0 here when the server reports no position (JSON {@code null} —
 * e.g. a {@code parse-xml} content error).</p>
 */
public record XQueryError(String code, int line, int column, String message) {

  /**
   * Parses the error envelope from a response body, or returns {@code null} if the body isn't an
   * XQuery error (not JSON, or lacking a {@code code}/{@code message}) — e.g. a results page.
   */
  public static XQueryError from(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JSONObject json = new JSONObject(body);
      String code = json.optString("code", null);
      // The clean human reason; "raw" is the original text, kept only as a last-resort fallback.
      String message = json.optString("message", json.optString("raw", null));
      if (code == null && message == null) {
        return null;
      }
      return new XQueryError(code, json.optInt("line", 0), json.optInt("column", 0), message);
    } catch (RuntimeException notJson) {
      return null;
    }
  }

  /**
   * Returns the failure as a user-facing message: the parsed XQuery error as prose when the cause
   * carries one, otherwise {@code prefix + ": " + cause message}.
   */
  public static String describe(String prefix, Throwable cause) {
    if (cause instanceof ExistHttpException http) {
      XQueryError error = from(http.getResponseBody());
      if (error != null) {
        return error.toDisplayString();
      }
    }
    return prefix + ": " + cause.getMessage();
  }

  /** A readable multi-line rendering: {@code XQuery error <code> (line L, column C)} + message. */
  public String toDisplayString() {
    StringBuilder out = new StringBuilder("XQuery error");
    if (code != null && !code.isBlank()) {
      out.append(' ').append(code);
    }
    if (line > 0) {
      out.append(" (line ").append(line);
      if (column > 0) {
        out.append(", column ").append(column);
      }
      out.append(')');
    }
    if (message != null && !message.isBlank()) {
      out.append("\n\n").append(message.strip());
    }
    return out.toString();
  }
}
