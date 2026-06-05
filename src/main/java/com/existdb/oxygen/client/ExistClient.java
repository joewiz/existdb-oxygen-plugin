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

import com.existdb.oxygen.model.ConnectionProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Thin HTTP client for the existdb-openapi surface. Talks plain HTTP/JSON with HTTP Basic auth.
 *
 * <p>Maps directly onto documented endpoints: {@code GET /api/db} for collection listings,
 * {@code GET/PUT /api/db/resource} for content, and the cursor-based {@code /api/query} family
 * for ad-hoc XQuery.</p>
 */
public final class ExistClient {

  private final ConnectionProfile profile;
  private final String authHeader;
  private final HttpClient http;

  public ExistClient(ConnectionProfile profile) {
    this.profile = profile;
    String creds = profile.getUser() + ":" + profile.getPassword();
    this.authHeader = "Basic " + Base64.getEncoder()
        .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    HttpClient.Builder builder = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15));
    if (profile.isAcceptSelfSigned()) {
      builder.sslContext(trustAllContext());
    }
    this.http = builder.build();
  }

  /**
   * An {@link SSLContext} that trusts any server certificate. Used only when the profile opts into
   * accepting self-signed / untrusted certs (eXist's default HTTPS listener ships a self-signed
   * cert). The certificate's host name must still match the URL; this only relaxes chain
   * verification, not host-name checking.
   */
  private static SSLContext trustAllContext() {
    TrustManager[] trustAll = {
      new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
          // Trust any client certificate.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
          // Trust any server certificate.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }
    };
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, trustAll, null);
      return ctx;
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new IllegalStateException("Cannot build a trust-all SSL context", e);
    }
  }

  public ConnectionProfile getProfile() {
    return profile;
  }

  // ---------------------------------------------------------------------------
  // Connectivity
  // ---------------------------------------------------------------------------

  /** GET /api/system/info — returns the raw JSON body. Throws on non-2xx. */
  public String systemInfo() throws IOException, InterruptedException {
    return send(request("/system/info").GET().build()).body();
  }

  /** GET /api/users/whoami — validates credentials and returns the identity JSON. */
  public JSONObject whoami() throws IOException, InterruptedException {
    return new JSONObject(send(request("/users/whoami").GET().build()).body());
  }

  /**
   * Returns the effective user name from {@code /api/users/whoami}. The response nests identity
   * under {@code effective} / {@code real} ({@code {"effective":{"user":"admin",...}}}).
   */
  public String whoamiUser() throws IOException, InterruptedException {
    JSONObject o = whoami();
    JSONObject effective = o.optJSONObject("effective");
    if (effective != null && effective.has("user")) {
      return effective.getString("user");
    }
    JSONObject real = o.optJSONObject("real");
    if (real != null && real.has("user")) {
      return real.getString("user");
    }
    return o.optString("user", profile.getUser());
  }

  // ---------------------------------------------------------------------------
  // Browsing
  // ---------------------------------------------------------------------------

  /** A child of a collection: either a sub-collection or a resource, with its full DB path. */
  public record ChildEntry(String name, String path, boolean collection) {
  }

  /**
   * Lists the direct children of a collection via {@code GET /api/db?path=...}. The response is a
   * collection object whose {@code children} array holds sub-collections first, then resources,
   * each already sorted by name.
   */
  public List<ChildEntry> listChildren(String collectionPath)
      throws IOException, InterruptedException {
    HttpResponse<String> r = send(request("/db?path=" + enc(collectionPath)).GET().build());
    JSONObject o = new JSONObject(r.body());
    JSONArray children = o.optJSONArray("children");
    List<ChildEntry> out = new ArrayList<>();
    if (children != null) {
      for (int i = 0; i < children.length(); i++) {
        JSONObject c = children.getJSONObject(i);
        boolean isCollection = "collection".equals(c.optString("type"));
        out.add(new ChildEntry(c.getString("name"), c.optString("path", null), isCollection));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Resources
  // ---------------------------------------------------------------------------

  /** A fetched resource's content and metadata. */
  public record ResourceContent(String content, boolean binary, String mimeType) {
  }

  /** GET /api/db/resource?path=... */
  public ResourceContent getResource(String dbPath) throws IOException, InterruptedException {
    HttpResponse<String> r = send(request("/db/resource?path=" + enc(dbPath)).GET().build());
    JSONObject o = new JSONObject(r.body());
    return new ResourceContent(
        o.optString("content", ""),
        o.optBoolean("binary", false),
        o.optString("mime-type", null));
  }

  /** DELETE /api/db/resource?path=… — removes a stored resource. Throws on non-2xx. */
  public void deleteResource(String dbPath) throws IOException, InterruptedException {
    send(request("/db/resource?path=" + enc(dbPath)).DELETE().build());
  }

  /** DELETE /api/db/collection?path=…&force=true — removes a collection and its contents. */
  public void deleteCollection(String dbPath) throws IOException, InterruptedException {
    send(request("/db/collection?path=" + enc(dbPath) + "&force=true").DELETE().build());
  }

  /** POST /api/db/collection — creates a collection (and any missing ancestors). */
  public void createCollection(String dbPath) throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    body.put("path", dbPath);
    send(request("/db/collection")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build());
  }

  /**
   * POST /api/db/move — relocates a resource/collection to {@code parentCollection}, keeping its
   * name (server-side, atomic). Requires the redesigned move/copy API (existdb-openapi PR #33);
   * returns 409 if the destination already exists. Throws on non-2xx.
   */
  public void move(String source, String parentCollection)
      throws IOException, InterruptedException {
    relocate("/db/move", source, parentCollection);
  }

  /** POST /api/db/copy — as {@link #move}, but copies. Requires the redesigned API (PR #33). */
  public void copy(String source, String parentCollection)
      throws IOException, InterruptedException {
    relocate("/db/copy", source, parentCollection);
  }

  private void relocate(String apiPath, String source, String parentCollection)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    body.put("source", source);
    body.put("parent", parentCollection);
    send(request(apiPath)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build());
  }

  /** PUT /api/db/resource — stores (creates or updates) a resource. Throws on non-2xx. */
  public void putResource(String dbPath, String content, String mimeType)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    body.put("path", dbPath);
    body.put("content", content);
    if (mimeType != null && !mimeType.isEmpty()) {
      body.put("mime-type", mimeType);
    }
    send(request("/db/resource")
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build());
  }

  // ---------------------------------------------------------------------------
  // Query execution (cursor-based)
  // ---------------------------------------------------------------------------

  /** A server-side query cursor with the total item count. */
  public record QueryHandle(String cursor, int items) {
  }

  /** POST /api/query — compiles and evaluates, returning a cursor over the results. */
  public QueryHandle runQuery(String query, String moduleLoadPath)
      throws IOException, InterruptedException {
    return runQuery(query, moduleLoadPath, null);
  }

  /**
   * POST /api/query with an optional {@code context-item} — a serialized node supplied as the
   * evaluation context so context-dependent expressions (e.g. {@code //para}) run against the
   * document the user is querying. When {@code contextItem} is null/blank the query evaluates with
   * no context item (the editor-content-is-the-query case). Servers without existdb-openapi PR #41
   * simply ignore the unknown field, so this is safe against older deployments.
   */
  public QueryHandle runQuery(String query, String moduleLoadPath, String contextItem)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    body.put("query", query);
    if (moduleLoadPath != null && !moduleLoadPath.isEmpty()) {
      body.put("module-load-path", moduleLoadPath);
    }
    if (contextItem != null && !contextItem.isBlank()) {
      body.put("context-item", contextItem);
    }
    HttpResponse<String> r = send(request("/query")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build());
    JSONObject o = new JSONObject(r.body());
    return new QueryHandle(o.optString("cursor", null), o.optInt("items", 0));
  }

  /**
   * GET /api/query/{id}/results — returns the raw JSON array body for a page of results.
   * Each element is an object with at least {@code value} and {@code type}.
   */
  public String fetchResultsRaw(String cursor, int start, int count, String method)
      throws IOException, InterruptedException {
    String path = "/query/" + enc(cursor) + "/results"
        + "?start=" + start + "&count=" + count + "&method=" + enc(method);
    return send(request(path).GET().build()).body();
  }

  /** DELETE /api/query/{id} — releases the server-side cursor. */
  public void closeCursor(String cursor) throws IOException, InterruptedException {
    send(request("/query/" + enc(cursor)).DELETE().build());
  }

  /**
   * Resolves a stored node's canonical path (the {@code fn:path()} XPath, e.g.
   * {@code /Q{ns}article[1]/Q{ns}para[3]}) from its document URI and eXist node id — both returned
   * by the cursor results. Used to locate the originating element in the opened source document.
   * Returns null when the node can no longer be resolved (e.g. the document changed).
   */
  public String nodePath(String documentUri, String nodeId)
      throws IOException, InterruptedException {
    String query = "fn:path(util:node-by-id(doc(\"" + xqEscape(documentUri) + "\"), \""
        + xqEscape(nodeId) + "\"))";
    QueryHandle handle = runQuery(query, null);
    if (handle.cursor() == null || handle.items() == 0) {
      return null;
    }
    try {
      String body = fetchResultsRaw(handle.cursor(), 1, 1, "adaptive");
      JSONArray array = new JSONArray(body);
      if (array.isEmpty()) {
        return null;
      }
      String value = array.getJSONObject(0).optString("value", "");
      // Adaptive serialization quotes strings; unwrap to the bare path.
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      return value.isEmpty() ? null : value;
    } finally {
      closeCursor(handle.cursor());
    }
  }

  /** Escapes a string for embedding in an XQuery double-quoted literal. */
  private static String xqEscape(String s) {
    return s.replace("\"", "\"\"");
  }

  // ---------------------------------------------------------------------------
  // Language services (existdb-openapi /api/langservice/*, LSP-isomorphic shapes)
  // ---------------------------------------------------------------------------

  /** A diagnostic. {@code line}/{@code column} are 0-based; {@code severity} is 1=error … 4=hint. */
  public record Diagnostic(int line, int column, int severity, String code, String message) {
  }

  /** A completion proposal. {@code kind} is an LSP {@code CompletionItemKind}. */
  public record Completion(String label, int kind, String detail, String documentation,
      String insertText) {
  }

  /** Hover info for a position: signature/documentation text plus the symbol kind. */
  public record Hover(String contents, String kind) {
  }

  /** A definition location. {@code line}/{@code column} are 0-based; {@code uri} is the DB path. */
  public record Definition(int line, int column, String name, String kind, String uri) {
  }

  /** POST /api/langservice/diagnostics — compile-checks an expression, returning any problems. */
  public List<Diagnostic> diagnostics(String expression, String moduleLoadPath)
      throws IOException, InterruptedException {
    JSONArray arr = new JSONArray(postLang("/langservice/diagnostics",
        langBody(expression, moduleLoadPath)));
    List<Diagnostic> out = new ArrayList<>(arr.length());
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.getJSONObject(i);
      out.add(new Diagnostic(o.optInt("line", 0), o.optInt("column", 0), o.optInt("severity", 1),
          o.optString("code", null), o.optString("message", "")));
    }
    return out;
  }

  /** POST /api/langservice/completions — proposals for an expression up to the cursor. */
  public List<Completion> completions(String expression, String moduleLoadPath)
      throws IOException, InterruptedException {
    JSONArray arr = new JSONArray(postLang("/langservice/completions",
        langBody(expression, moduleLoadPath)));
    List<Completion> out = new ArrayList<>(arr.length());
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.getJSONObject(i);
      out.add(new Completion(o.optString("label", ""), o.optInt("kind", 0),
          o.optString("detail", null), o.optString("documentation", null),
          o.optString("insertText", o.optString("label", ""))));
    }
    return out;
  }

  /** POST /api/langservice/hover — signature/docs at a position, or {@code null} if none. */
  public Hover hover(String expression, int line, int column, String moduleLoadPath)
      throws IOException, InterruptedException {
    JSONObject body = langBody(expression, moduleLoadPath);
    body.put("line", line);
    body.put("column", column);
    JSONObject o = asObject(postLang("/langservice/hover", body));
    if (o == null) {
      return null;
    }
    String contents = o.optString("contents", "");
    return contents.isEmpty() ? null : new Hover(contents, o.optString("kind", null));
  }

  /** POST /api/langservice/definition — the symbol's definition site, or {@code null} if none. */
  public Definition definition(String expression, int line, int column, String moduleLoadPath)
      throws IOException, InterruptedException {
    JSONObject body = langBody(expression, moduleLoadPath);
    body.put("line", line);
    body.put("column", column);
    JSONObject o = asObject(postLang("/langservice/definition", body));
    if (o == null) {
      return null;
    }
    String name = o.optString("name", "");
    return name.isEmpty() ? null
        : new Definition(o.optInt("line", 0), o.optInt("column", 0), name,
            o.optString("kind", null), o.optString("uri", null));
  }

  /** Parses an object body, or returns {@code null} for an empty/{@code null}/non-object response. */
  private static JSONObject asObject(String body) {
    if (body == null) {
      return null;
    }
    String trimmed = body.strip();
    return trimmed.startsWith("{") ? new JSONObject(trimmed) : null;
  }

  private JSONObject langBody(String expression, String moduleLoadPath) {
    JSONObject body = new JSONObject();
    body.put("expression", expression);
    if (moduleLoadPath != null && !moduleLoadPath.isEmpty()) {
      body.put("module-load-path", moduleLoadPath);
    }
    return body;
  }

  private String postLang(String path, JSONObject body) throws IOException, InterruptedException {
    return send(request(path)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build()).body();
  }

  // ---------------------------------------------------------------------------
  // Low-level HTTP
  // ---------------------------------------------------------------------------

  private HttpRequest.Builder request(String apiPath) {
    return HttpRequest.newBuilder()
        .uri(URI.create(profile.getApiRoot() + apiPath))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", authHeader)
        .header("Accept", "application/json");
  }

  private HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
    HttpResponse<String> resp = sendPrivileged(req);
    int code = resp.statusCode();
    if (code < 200 || code >= 300) {
      throw new ExistHttpException(code, req.method() + " " + req.uri().getPath(), resp.body());
    }
    return resp;
  }

  /**
   * Sends the request inside a privileged block. Oxygen runs validation/transformation engines
   * under a restricted {@code SecurityManager}; without this the HTTP call is denied
   * {@code java.net.URLPermission} when diagnostics run from the XQuery engine.
   */
  @SuppressWarnings("removal")
  private HttpResponse<String> sendPrivileged(HttpRequest req)
      throws IOException, InterruptedException {
    try {
      return java.security.AccessController.doPrivileged(
          (java.security.PrivilegedExceptionAction<HttpResponse<String>>) () ->
              http.send(req, HttpResponse.BodyHandlers.ofString()));
    } catch (java.security.PrivilegedActionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      if (cause instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      }
      throw new IOException(cause);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
