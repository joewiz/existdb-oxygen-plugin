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
  private volatile String lastPackageBody;
  private volatile String lastSearchQuery;
  private volatile String lastResourcePutQuery;
  private volatile String lastResourceGetQuery;
  private volatile String lastExportQuery;

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

    handle(prefix + "/db/resource", this::handleResource);
    // Longest-prefix match wins, so this beats /db/collection for export requests.
    handle(prefix + "/db/collection/export", this::handleExport);

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

    registerSearchHandlers(prefix);

    handle(prefix + "/langservice/diagnostics", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "[{\"line\":0,\"column\":5,\"severity\":1,"
          + "\"code\":\"XPST0003\",\"message\":\"unexpected token\"}]");
    });
    handle(prefix + "/langservice/completions", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "[{\"label\":\"fn:count#1\",\"kind\":3,"
          + "\"detail\":\"fn:count($arg as item()*) as xs:integer\","
          + "\"documentation\":\"Returns the number of items\","
          + "\"insertText\":\"fn:count(${1:\\\\$arg})\",\"insertTextFormat\":2}]");
    });
    handle(prefix + "/langservice/hover", ex -> {
      lastLangBody = readBody(ex);
      // LSP MarkupContent (existdb-openapi #44): contents is an object, not a flat string.
      respond(ex, 200, "{\"contents\":{\"kind\":\"markdown\","
          + "\"value\":\"```xquery\\nfn:count($arg as item()*) as xs:integer\\n```\\n\\n"
          + "Returns the number of items.\"}}");
    });
    handle(prefix + "/langservice/signature-help", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "{\"signatures\":[{\"label\":"
          + "\"fn:count($arg as item()*) as xs:integer\","
          + "\"documentation\":{\"kind\":\"markdown\",\"value\":\"Counts items.\"},"
          + "\"parameters\":[{\"label\":\"$arg as item()*\","
          + "\"documentation\":{\"kind\":\"markdown\",\"value\":\"The input sequence\"}}]}],"
          + "\"activeSignature\":0,\"activeParameter\":0}");
    });
    handle(prefix + "/langservice/definition", ex -> {
      lastLangBody = readBody(ex);
      respond(ex, 200, "{\"line\":5,\"column\":0,\"name\":\"local:my-func#1\","
          + "\"kind\":\"function\",\"uri\":\"/db/apps/myapp/lib.xqm\"}");
    });

    registerPackageHandlers(prefix);

    server.start();
  }

  /**
   * Registers the {@code /search*} endpoints. Extracted from the constructor so it stays under PMD's
   * NPath threshold (the branchy 403 handler multiplies quickly). {@code /search/fields} is
   * registered first so the longest-prefix match routes it there rather than to {@code /search}.
   */
  private void registerSearchHandlers(String prefix) {
    handle(prefix + "/search/fields", ex -> respond(ex, 200,
        "{\"scope\":[\"/db\"],\"user\":\"admin\",\"total\":3,\"fields\":["
            + "{\"kind\":\"facet\",\"field\":\"site-app\",\"elements\":[\"post\",\"topic\"]},"
            + "{\"kind\":\"field\",\"field\":\"category\",\"type\":\"xs:string\","
            + "\"elements\":[\"entry\"],\"returnable\":true,"
            + "\"analyzer\":\"org.apache.lucene.analysis.standard.StandardAnalyzer\"},"
            + "{\"kind\":\"field\",\"field\":\"site-content\",\"type\":\"xs:string\","
            + "\"elements\":[\"post\"],\"returnable\":true,"
            + "\"analyzer\":[\"org.apache.lucene.analysis.standard.StandardAnalyzer\","
            + "\"org.apache.lucene.analysis.core.SimpleAnalyzer\"]},"
            + "{\"kind\":\"vector\",\"field\":\"test-embedding\",\"elements\":[\"doc\"]}]}"));

    handle(prefix + "/search", ex -> {
      lastSearchQuery = ex.getRequestURI().getRawQuery();
      String q = String.valueOf(lastSearchQuery);
      if (q.contains("field=secret") || q.contains("vector=secret")) {
        // Field-level security: a field/vector the caller may not see is refused.
        respond(ex, 403, "{\"error\":\"forbidden\"}");
      } else if (q.contains("vector=")) {
        respond(ex, 200, searchVectorBody());
      } else {
        respond(ex, 200, searchKeywordBody());
      }
    });
  }

  private static String searchKeywordBody() {
    return "{\"total\":7,\"query\":\"index\",\"results\":["
        + "{\"app\":\"doc\",\"title\":\"(untitled)\",\"snippet\":\"about indexes\","
        + "\"path\":\"/db/apps/doc/indexing.xml\"},"
        + "{\"app\":\"doc\",\"title\":\"Tuning\",\"snippet\":\"range index\","
        + "\"path\":\"/db/apps/doc/tuning.xml\"}],"
        + "\"facets\":{\"site-app\":{\"docs\":5,\"blog\":2},\"site-section\":{\"guide\":4}}}";
  }

  /** A vector ("Similar to…") response (existdb-openapi#60): top-k ranked, no facets, span snippets. */
  private static String searchVectorBody() {
    return "{\"query\":\"speed\",\"field\":\"site-embedding\",\"model\":\"all-MiniLM-L6-v2\","
        + "\"k\":20,\"total\":2,\"max-score\":0.9,\"results\":["
        + "{\"app\":\"doc\",\"title\":\"Tuning\",\"score\":0.9,\"path\":\"/db/apps/doc/tuning.xml\","
        + "\"uri\":\"/db/apps/doc/tuning.xml\",\"snippet\":\"<span>performance tuning</span>\"},"
        + "{\"app\":\"doc\",\"title\":\"Indexing\",\"score\":0.7,"
        + "\"path\":\"/db/apps/doc/indexing.xml\",\"snippet\":\"<span>index config</span>\"}]}";
  }

  /**
   * Registers the {@code /packages*} endpoints. Extracted from the constructor so each method stays
   * under PMD's NPath threshold (the canned multi-branch handlers multiply quickly). createContext
   * matches the longest registered prefix, so {@code /packages/install} and
   * {@code /packages/update-check} win for their paths and {@code /packages} handles the list (GET)
   * plus remove (DELETE {@code /packages/{abbrev}}).
   */
  private void registerPackageHandlers(String prefix) {
    handle(prefix + "/packages", ex -> {
      String path = ex.getRequestURI().getPath();
      if ("GET".equals(ex.getRequestMethod()) && path.endsWith("/api/packages")) {
        respond(ex, 200, "[{\"name\":\"http://e-editiones.org/roaster\",\"abbrev\":\"roaster\","
            + "\"version\":\"1.12.1\",\"title\":\"Roaster\",\"type\":\"library\","
            + "\"description\":\"OpenAPI router\",\"authors\":[\"e-editiones\"],"
            + "\"website\":\"https://e-editiones.org\"},"
            + "{\"name\":\"http://www.functx.com\",\"abbrev\":\"functx\",\"version\":\"1.0.0\","
            + "\"title\":\"FunctX\",\"type\":\"library\",\"description\":\"\",\"authors\":[],"
            + "\"website\":\"\"}]");
      } else if ("DELETE".equals(ex.getRequestMethod())) {
        boolean force = String.valueOf(ex.getRequestURI().getRawQuery()).contains("force=true");
        if (path.endsWith("/roaster") && !force) {
          respond(ex, 200, "{\"error\":\"Cannot remove: other packages depend on it\","
              + "\"dependents\":[\"http://exist-db.org/apps/eXide\"],"
              + "\"hint\":\"Use force=true to remove anyway\"}");
        } else if (path.endsWith("/ghost")) {
          respond(ex, 200, "{\"error\":\"Package not found: ghost\"}");
        } else {
          respond(ex, 200, "{\"name\":\"x\",\"undeploy\":true,\"remove\":true}");
        }
      } else {
        respond(ex, 405, "{}");
      }
    });
    handle(prefix + "/packages/install", ex -> {
      lastPackageBody = readBody(ex);
      if (lastPackageBody.contains("\"name\":\"fail\"")) {
        respond(ex, 200, "{\"success\":false,\"error\":{\"code\":\"err:FAIL\","
            + "\"description\":\"could not install\"}}");
      } else {
        respond(ex, 200, "{\"success\":true,\"result\":{\"name\":\"x\",\"version\":\"\","
            + "\"target\":\"/db/system/repo/x\"}}");
      }
    });
    handle(prefix + "/packages/update-check", ex -> {
      lastPackageBody = readBody(ex);
      respond(ex, 200, "{\"registry\":\"https://exist-db.org/exist/apps/public-repo\","
          + "\"updates\":[{\"name\":\"http://www.functx.com\",\"abbrev\":\"functx\","
          + "\"installed\":\"1.0.0\",\"available\":\"2.0.0\"}]}");
    });
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/exist/apps/existdb-openapi";
  }

  String lastPutBody() {
    return lastPutBody;
  }

  String lastResourcePutQuery() {
    return lastResourcePutQuery;
  }

  String lastResourceGetQuery() {
    return lastResourceGetQuery;
  }

  String lastExportQuery() {
    return lastExportQuery;
  }

  String lastSearchQuery() {
    return lastSearchQuery;
  }

  String lastQueryBody() {
    return lastQueryBody;
  }

  String lastLangBody() {
    return lastLangBody;
  }

  String lastPackageBody() {
    return lastPackageBody;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** GET/PUT/DELETE on the consolidated content endpoint (existdb-openapi#59). */
  private void handleResource(HttpExchange ex) throws IOException {
    String query = ex.getRequestURI().getRawQuery();
    if (query != null && query.contains("missing")) {
      respond(ex, 404, "{\"error\":\"not found\"}");
    } else if ("GET".equals(ex.getRequestMethod())) {
      lastResourceGetQuery = query; // serialization params (if any) ride the query string
      respondAs(ex, 200, "xquery version \"3.1\"; 42", "application/xquery");
    } else if ("PUT".equals(ex.getRequestMethod())) {
      lastResourcePutQuery = query; // path/mime now ride the query, not the body
      lastPutBody = readBody(ex);   // the body is the raw content
      respond(ex, 201, "{\"path\":\"/db/x.xq\"}");
    } else if ("DELETE".equals(ex.getRequestMethod())) {
      respond(ex, 200, "{\"deleted\":true}");
    } else {
      respond(ex, 405, "{}");
    }
  }

  /** GET /api/db/collection/export — a fake zip/xar archive with a Content-Disposition file name. */
  private void handleExport(HttpExchange ex) throws IOException {
    lastExportQuery = ex.getRequestURI().getRawQuery();
    String fmt = String.valueOf(lastExportQuery).contains("format=xar") ? "xar" : "zip";
    String name = "xar".equals(fmt) ? "myapp-1.0.xar" : "coll.zip";
    byte[] body = ("PK-fake-archive-" + fmt).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/zip");
    ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + name + "\"");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
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
    respondAs(ex, code, body, "application/json");
  }

  /** Responds with an explicit {@code Content-Type} (e.g. raw resource content, not JSON). */
  private static void respondAs(HttpExchange ex, int code, String body, String contentType)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", contentType);
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
