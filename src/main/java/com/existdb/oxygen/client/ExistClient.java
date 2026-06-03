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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
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
    JSONObject body = new JSONObject();
    body.put("query", query);
    if (moduleLoadPath != null && !moduleLoadPath.isEmpty()) {
      body.put("module-load-path", moduleLoadPath);
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
    JSONObject o = new JSONObject(postLang("/langservice/hover", body));
    String contents = o.optString("contents", "");
    return contents.isEmpty() ? null : new Hover(contents, o.optString("kind", null));
  }

  /** POST /api/langservice/definition — the symbol's definition site, or {@code null} if none. */
  public Definition definition(String expression, int line, int column, String moduleLoadPath)
      throws IOException, InterruptedException {
    JSONObject body = langBody(expression, moduleLoadPath);
    body.put("line", line);
    body.put("column", column);
    JSONObject o = new JSONObject(postLang("/langservice/definition", body));
    String name = o.optString("name", "");
    return name.isEmpty() ? null
        : new Definition(o.optInt("line", 0), o.optInt("column", 0), name,
            o.optString("kind", null), o.optString("uri", null));
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
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    int code = resp.statusCode();
    if (code < 200 || code >= 300) {
      throw new ExistHttpException(code, req.method() + " " + req.uri().getPath(), resp.body());
    }
    return resp;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
