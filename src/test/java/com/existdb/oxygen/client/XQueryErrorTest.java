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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class XQueryErrorTest {

  // The #71 static-error envelope from /api/query for "1 to" (XPST0003): clean message + 1-based
  // user-relative line/column + the original text under "raw".
  private static final String STATIC_ERROR = """
      {
        "code": "err:XPST0003",
        "message": "unexpected token: null",
        "line": 1,
        "column": 5,
        "raw": "It is a static error ... unexpected token: null [at line 1, column 5]"
      }""";

  @Test
  void parsesCodeMessageAndUserRelativePosition() {
    XQueryError error = XQueryError.from(STATIC_ERROR);
    assertEquals("err:XPST0003", error.code());
    assertEquals(1, error.line());
    assertEquals(5, error.column());
    assertEquals("unexpected token: null", error.message());
  }

  @Test
  void rendersAsReadableProseNotJson() {
    String shown = XQueryError.from(STATIC_ERROR).toDisplayString();
    assertEquals("XQuery error err:XPST0003 (line 1, column 5)\n\nunexpected token: null", shown);
    assertFalse(shown.contains("{"), shown); // no raw JSON braces
    assertFalse(shown.contains("XPathException"), shown); // no leaked Java class name
  }

  @Test
  void nullPositionShowsMessageWithoutLocation() {
    // parse-xml content errors report line/column as null — show the message, no caret jump.
    String body = """
        {"code":"err:FODC0006","message":"String passed to fn:parse-xml is not well-formed.",
         "line":null,"column":null,"raw":"..."}""";
    assertEquals("XQuery error err:FODC0006\n\nString passed to fn:parse-xml is not well-formed.",
        XQueryError.from(body).toDisplayString());
  }

  @Test
  void nonErrorBodiesAreNotMisread() {
    assertNull(XQueryError.from("[{\"value\":1,\"type\":\"xs:integer\"}]")); // a results page
    assertNull(XQueryError.from("not json at all"));
    assertNull(XQueryError.from(""));
    assertNull(XQueryError.from(null));
  }

  @Test
  void describeFormatsAQueryErrorFromAnHttpException() {
    // Query errors are now HTTP 400 (xqt-errors) rather than 500; describe keys off the body, not status.
    ExistHttpException http = new ExistHttpException(400, "POST /api/query", STATIC_ERROR);
    String shown = XQueryError.describe("Query failed", http);
    assertTrue(shown.startsWith("XQuery error err:XPST0003 (line 1, column 5)"), shown);
    assertFalse(shown.contains("HTTP 400"), shown);
  }

  @Test
  void describeFallsBackForNonQueryFailures() {
    String shown = XQueryError.describe("Query failed", new IOException("boom"));
    assertEquals("Query failed: boom", shown);
  }

  @Test
  void describeFallsBackForHttpErrorsWithoutAQueryEnvelope() {
    ExistHttpException http = new ExistHttpException(404, "GET /db", "not found");
    String shown = XQueryError.describe("Query failed", http);
    assertTrue(shown.startsWith("Query failed: HTTP 404"), shown);
  }
}
