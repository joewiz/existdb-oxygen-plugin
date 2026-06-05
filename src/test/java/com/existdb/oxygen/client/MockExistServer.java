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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A minimal in-process stand-in for existdb-openapi, built on the JDK's {@link HttpServer}. Lets
 * the unit tests exercise {@link ExistClient} against canned responses with no eXist or network —
 * the same approach the manual verification harness used, promoted into the test suite.
 */
public final class MockExistServer implements AutoCloseable {

  private final HttpServer server;
  private volatile String lastPutBody;
  private volatile String lastQueryBody;
  private volatile String lastLangBody;

  public MockExistServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String prefix = "/exist/apps/existdb-openapi/api";

    handle(prefix + "/system/info", ex -> respond(ex, 200, "{\"db\":{\"version\":\"7.0.0\"}}"));
    handle(prefix + "/users/whoami", ex ->
        respond(ex, 200, "{\"effective\":{\"user\":\"admin\",\"groups\":[\"dba\"]},"
            + "\"real\":{\"user\":\"admin\",\"groups\":[\"dba\"]}}"));

    // GET /api/db (the listing) — distinct from /api/db/resource (longest-prefix wins below).
    handle(prefix + "/db", ex -> {
      if (ex.getRequestURI().getPath().endsWith("/api/db")) {
        respond(ex, 200, "{\"type\":\"collection\",\"name\":\"db\",\"path\":\"/db\",\"children\":["
            + "{\"type\":\"collection\",\"name\":\"apps\",\"path\":\"/db/apps\"},"
            + "{\"type\":\"resource\",\"name\":\"index.xq\",\"path\":\"/db/index.xq\"}]}");
      } else {
        respond(ex, 404, "{}");
      }
    });

    handle(prefix + "/db/resource", ex -> {
      String query = ex.getRequestURI().getRawQuery();
      if (query != null && query.contains("missing")) {
        respond(ex, 404, "{\"error\":\"not found\"}");
      } else if ("GET".equals(ex.getRequestMethod())) {
        respond(ex, 200, "{\"path\":\"/db/x.xq\",\"binary\":false,"
            + "\"content\":\"xquery version \\\"3.1\\\"; 42\",\"mime-type\":\"application/xquery\"}");
      } else if ("PUT".equals(ex.getRequestMethod())) {
        lastPutBody = readBody(ex);
        respond(ex, 201, "{\"stored\":\"/db/x.xq\"}");
      } else if ("DELETE".equals(ex.getRequestMethod())) {
        respond(ex, 200, "{\"deleted\":true}");
      } else {
        respond(ex, 405, "{}");
      }
    });

    handle(prefix + "/db/collection", ex -> {
      if ("DELETE".equals(ex.getRequestMethod())) {
        respond(ex, 200, "{\"deleted\":true}");
      } else if ("POST".equals(ex.getRequestMethod())) {
        respond(ex, 201, "{\"created\":true}");
      } else {
        respond(ex, 405, "{}");
      }
    });

    handle(prefix + "/db/move", ex -> {
      lastPutBody = readBody(ex);
      respond(ex, 200, "{\"moved\":true}");
    });
    handle(prefix + "/db/copy", ex -> {
      lastPutBody = readBody(ex);
      respond(ex, 200, "{\"copied\":true}");
    });

    handle(prefix + "/query", ex -> {
      String path = ex.getRequestURI().getPath();
      if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/api/query")) {
        lastQueryBody = readBody(ex);
        respond(ex, 200, "{\"cursor\":\"C1\",\"items\":3}");
      } else if (path.endsWith("/results")) {
        respond(ex, 200, "[{\"type\":\"xs:integer\",\"value\":\"1\"},"
            + "{\"type\":\"xs:integer\",\"value\":\"2\"},{\"type\":\"xs:integer\",\"value\":\"3\"}]");
      } else if ("DELETE".equals(ex.getRequestMethod())) {
        respond(ex, 200, "{\"closed\":true}");
      } else {
        respond(ex, 404, "{}");
      }
    });

    handle(prefix + "/search", ex -> respond(ex, 200,
        "{\"total\":7,\"query\":\"index\",\"results\":["
            + "{\"app\":\"doc\",\"title\":\"(untitled)\",\"snippet\":\"about indexes\","
            + "\"path\":\"/db/apps/doc/indexing.xml\"},"
            + "{\"app\":\"doc\",\"title\":\"Tuning\",\"snippet\":\"range index\","
            + "\"path\":\"/db/apps/doc/tuning.xml\"}]}"));

    handle(prefix + "/langservice/diagnostics", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "[{\"line\":0,\"column\":5,\"severity\":1,"
          + "\"code\":\"XPST0003\",\"message\":\"unexpected token\"}]");
    });
    handle(prefix + "/langservice/completions", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "[{\"label\":\"fn:count#1\",\"kind\":3,"
          + "\"detail\":\"fn:count($arg as item()*) as xs:integer\","
          + "\"documentation\":\"Returns the number of items\",\"insertText\":\"fn:count()\"}]");
    });
    handle(prefix + "/langservice/hover", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "{\"contents\":\"fn:count(...)\",\"kind\":\"function\"}");
    });
    handle(prefix + "/langservice/definition", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "{\"line\":5,\"column\":0,\"name\":\"local:my-func#1\","
          + "\"kind\":\"function\",\"uri\":\"/db/apps/myapp/lib.xqm\"}");
    });

    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/exist/apps/existdb-openapi";
  }

  String lastPutBody() {
    return lastPutBody;
  }

  String lastQueryBody() {
    return lastQueryBody;
  }

  String lastLangBody() {
    return lastLangBody;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(String path, HttpHandler handler) {
    server.createContext(path, handler);
  }

  private static String readBody(HttpExchange ex) throws IOException {
    try (InputStream is = ex.getRequestBody()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      is.transferTo(out);
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange ex, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
