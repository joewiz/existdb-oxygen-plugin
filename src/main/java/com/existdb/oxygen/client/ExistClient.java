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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
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
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

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

  /** A resource's raw bytes plus the server-declared MIME type (from the streaming endpoint). */
  public record RawResource(byte[] bytes, String mimeType) {
  }

  /**
   * GET /api/db/resource/{path} — fetches the resource's <em>raw bytes</em> (path-in-URL streaming
   * endpoint). Correct for binary resources (images, PDFs, fonts) where the JSON envelope's text
   * {@code content} is lossy. Throws {@link ExistHttpException} on non-2xx (404 for a missing
   * resource).
   */
  public RawResource getResourceBytes(String dbPath) throws IOException, InterruptedException {
    HttpRequest req = request("/db/resource/" + encPath(dbPath)).GET().build();
    HttpResponse<byte[]> resp = sendBytes(req);
    int code = resp.statusCode();
    if (code < 200 || code >= 300) {
      throw new ExistHttpException(code, req.method() + " " + req.uri().getPath(),
          new String(resp.body(), StandardCharsets.UTF_8));
    }
    return new RawResource(resp.body(), resp.headers().firstValue("Content-Type").orElse(null));
  }

  /** DELETE /api/db/resource?path=… — removes a stored resource. Throws on non-2xx. */
  public void deleteResource(String dbPath) throws IOException, InterruptedException {
    send(request("/db/resource?path=" + enc(dbPath)).DELETE().build());
  }

  /** DELETE /api/db/collection?path=…&force=true — removes a collection and its contents. */
  public void deleteCollection(String dbPath) throws IOException, InterruptedException {
    send(request("/db/collection?path=" + enc(dbPath) + "&force=true").DELETE().build());
  }

  /** Owner, group, and symbolic mode (e.g. {@code rwxr-xr-x}) of a stored resource/collection. */
  public record Permissions(String owner, String group, String mode) {
  }

  /** GET /api/db/properties?path=… — reads a resource/collection's owner, group, and mode. */
  public Permissions getPermissions(String dbPath) throws IOException, InterruptedException {
    HttpResponse<String> r = send(request("/db/properties?path=" + enc(dbPath)).GET().build());
    JSONObject o = new JSONObject(r.body());
    return new Permissions(
        o.optString("owner", ""), o.optString("group", ""), o.optString("mode", ""));
  }

  /**
   * POST /api/db/permissions — sets owner, group, and/or symbolic {@code mode} (e.g.
   * {@code rwxr-xr-x}) on a resource/collection. Blank fields are omitted (left unchanged). Throws
   * on non-2xx (e.g. 403 when the current user can't change them).
   */
  public void setPermissions(String dbPath, String owner, String group, String mode)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject().put("path", dbPath);
    if (owner != null && !owner.isEmpty()) {
      body.put("owner", owner);
    }
    if (group != null && !group.isEmpty()) {
      body.put("group", group);
    }
    if (mode != null && !mode.isEmpty()) {
      body.put("mode", mode);
    }
    post("/db/permissions", body);
  }

  /** GET /api/users — the user account names (for the permissions owner picker). */
  public List<String> listUsers() throws IOException, InterruptedException {
    return names(send(request("/users").GET().build()).body());
  }

  /** GET /api/groups — the group names (for the permissions group picker). */
  public List<String> listGroups() throws IOException, InterruptedException {
    return names(send(request("/groups").GET().build()).body());
  }

  /** Extracts the {@code name} of each object in a JSON array body, dropping any blanks. */
  private static List<String> names(String body) {
    JSONArray arr = new JSONArray(body);
    List<String> out = new ArrayList<>(arr.length());
    for (int i = 0; i < arr.length(); i++) {
      String name = arr.getJSONObject(i).optString("name", "");
      if (!name.isEmpty()) {
        out.add(name);
      }
    }
    return out;
  }

  /**
   * POST /api/db/collection — creates a single collection. Idempotent if it already exists, but the
   * parent must exist: the server does <em>not</em> create missing ancestors (creating
   * {@code /a/b/c} when {@code /a/b} is absent fails). Create intermediate levels top-down.
   */
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

  /**
   * POST /api/db/move with {@code newName} — renames a resource/collection in place (server-side,
   * atomic). Requires the redesigned move/copy API (existdb-openapi PR #33); returns 409 if the new
   * name is already taken. Throws on non-2xx.
   */
  public void rename(String source, String newName)
      throws IOException, InterruptedException {
    renameOrDuplicate("/db/move", source, newName);
  }

  /** POST /api/db/copy with {@code newName} — duplicates in place under a new name (PR #33). */
  public void duplicate(String source, String newName)
      throws IOException, InterruptedException {
    renameOrDuplicate("/db/copy", source, newName);
  }

  private void relocate(String apiPath, String source, String parentCollection)
      throws IOException, InterruptedException {
    post(apiPath, new JSONObject().put("source", source).put("parent", parentCollection));
  }

  private void renameOrDuplicate(String apiPath, String source, String newName)
      throws IOException, InterruptedException {
    post(apiPath, new JSONObject().put("source", source).put("newName", newName));
  }

  private void post(String apiPath, JSONObject body) throws IOException, InterruptedException {
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

  /**
   * PUT /api/db/resource/{path} — stores a resource's <em>raw bytes</em> (path-in-URL streaming
   * endpoint). Binary-safe, so use it for genuinely-binary resources (images, PDFs, fonts) that the
   * JSON {@link #putResource} would corrupt by round-tripping through text. Throws on non-2xx.
   */
  public void putResourceBytes(String dbPath, byte[] bytes, String mimeType)
      throws IOException, InterruptedException {
    send(request("/db/resource/" + encPath(dbPath))
        .header("Content-Type", mimeType != null && !mimeType.isEmpty()
            ? mimeType : "application/octet-stream")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        .build());
  }

  /** A resource read as bytes (correctly text-or-binary) with its mime-type and binary flag. */
  public record ResourceBytes(byte[] bytes, String mimeType, boolean binary) {
  }

  /**
   * Reads a resource's bytes correctly for round-tripping: textual types (XQuery, XML, JSON,
   * {@code text/*}) from the JSON envelope's UTF-8 content, and genuinely-binary types from the raw
   * streaming endpoint. This avoids both lossy text round-tripping of binaries and the streaming
   * endpoint's execution of XQuery modules.
   */
  public ResourceBytes readResource(String dbPath) throws IOException, InterruptedException {
    ResourceContent rc = getResource(dbPath);
    String mime = rc.mimeType() != null ? rc.mimeType() : MimeTypes.byName(dbPath);
    if (rc.binary() && !MimeTypes.isTextual(mime)) {
      RawResource raw = getResourceBytes(dbPath);
      return new ResourceBytes(raw.bytes(), raw.mimeType() != null ? raw.mimeType() : mime, true);
    }
    return new ResourceBytes(rc.content().getBytes(StandardCharsets.UTF_8), mime, false);
  }

  // ---------------------------------------------------------------------------
  // Packages (EXPath / eXist apps)
  // ---------------------------------------------------------------------------

  /** An installed package: {@code abbrev} is the id used on {@code /packages/{name}} operations. */
  public record PackageInfo(String name, String abbrev, String title, String version,
      String type, String description, List<String> authors, String website) {
  }

  /** An available update for an installed package (from {@code /packages/update-check}). */
  public record PackageUpdate(String name, String abbrev, String installed, String available) {
  }

  /** The registry URL checked plus the list of packages with newer versions available. */
  public record UpdateCheck(String registry, List<PackageUpdate> updates) {
  }

  /**
   * Outcome of a {@link #removePackage} call: {@code removed} is true on success; otherwise
   * {@code dependents} lists the packages blocking removal (empty when blocked for another reason,
   * e.g. not found) and {@code message} carries the server's explanation.
   */
  public record RemoveResult(boolean removed, List<String> dependents, String message) {
  }

  /** GET /api/packages — the installed packages, by title. */
  public List<PackageInfo> listPackages() throws IOException, InterruptedException {
    JSONArray arr = new JSONArray(send(request("/packages").GET().build()).body());
    List<PackageInfo> out = new ArrayList<>(arr.length());
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.getJSONObject(i);
      List<String> authors = new ArrayList<>();
      JSONArray a = o.optJSONArray("authors");
      for (int j = 0; a != null && j < a.length(); j++) {
        authors.add(a.optString(j, ""));
      }
      out.add(new PackageInfo(o.optString("name", ""), o.optString("abbrev", ""),
          o.optString("title", ""), o.optString("version", ""), o.optString("type", ""),
          o.optString("description", ""), authors, o.optString("website", "")));
    }
    return out;
  }

  /**
   * DELETE /api/packages/{abbrev} — uninstalls a package. The server replies 200 even when it
   * declines: with {@code force == false} a still-depended-on package yields a body listing the
   * {@code dependents} (and is <em>not</em> removed); pass {@code force == true} to remove anyway.
   * The parsed {@link RemoveResult} distinguishes success from a dependents/not-found refusal.
   */
  public RemoveResult removePackage(String abbrev, boolean force)
      throws IOException, InterruptedException {
    JSONObject o = new JSONObject(
        send(request("/packages/" + enc(abbrev) + "?force=" + force).DELETE().build()).body());
    if (o.has("dependents")) {
      List<String> deps = new ArrayList<>();
      JSONArray a = o.optJSONArray("dependents");
      for (int i = 0; a != null && i < a.length(); i++) {
        deps.add(a.optString(i, ""));
      }
      return new RemoveResult(false, deps, o.optString("error", "Cannot remove: other packages depend on it"));
    }
    if (o.has("error")) {
      return new RemoveResult(false, List.of(), o.optString("error", "Remove failed"));
    }
    return new RemoveResult(true, List.of(), null);
  }

  /**
   * POST /api/packages/install (multipart) — installs a package from a local {@code .xar}.
   *
   * <p>The existdb-openapi {@code packages:install} handler does not yet read the multipart
   * {@code file} part (only the JSON registry path is implemented), so this currently has no effect
   * server-side; the Package Manager's "Install .xar…" action stays disabled until the server ships
   * the multipart branch (tasking filed 2026-06-06). Kept here so enabling it is a one-line change.
   */
  public void installPackageFile(String filename, byte[] xar)
      throws IOException, InterruptedException {
    String boundary = "----existdb-oxygen-" + UUID.randomUUID();
    byte[] body = multipartFile(boundary, "file", filename, xar);
    send(request("/packages/install")
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build());
  }

  /**
   * POST /api/packages/install (JSON) — installs/updates a package from a registry by {@code name}
   * (its URI) and {@code findUrl} (the registry's {@code /find} endpoint, both required by the
   * server), optionally pinning {@code version} (empty installs the latest). Used to apply an
   * available update from {@link #checkPackageUpdates()}.
   */
  public void installPackage(String name, String findUrl, String version)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject().put("name", name).put("url", findUrl);
    if (version != null && !version.isEmpty()) {
      body.put("version", version);
    }
    JSONObject o = new JSONObject(send(request("/packages/install")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build()).body());
    if (!o.optBoolean("success", false)) {
      throw new IOException("Install failed: " + installError(o));
    }
  }

  /** Extracts a human-readable message from an install response's {@code error} object/string. */
  private static String installError(JSONObject response) {
    Object error = response.opt("error");
    if (error instanceof JSONObject obj) {
      return obj.optString("description", obj.optString("code", "unknown error"));
    }
    return error != null ? error.toString() : "unknown error";
  }

  /** POST /api/packages/update-check — the registry and any installed packages with newer versions. */
  public UpdateCheck checkPackageUpdates() throws IOException, InterruptedException {
    return checkPackageUpdates(null);
  }

  /**
   * POST /api/packages/update-check against a specific {@code registry} base URL (or the server's
   * default public-repo when {@code null}/blank). Returns the registry checked and any installed
   * packages with newer versions available there.
   */
  public UpdateCheck checkPackageUpdates(String registry) throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    if (registry != null && !registry.isEmpty()) {
      body.put("registry", registry);
    }
    JSONObject o = new JSONObject(send(request("/packages/update-check")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build()).body());
    List<PackageUpdate> updates = new ArrayList<>();
    JSONArray arr = o.optJSONArray("updates");
    for (int i = 0; arr != null && i < arr.length(); i++) {
      JSONObject u = arr.getJSONObject(i);
      updates.add(new PackageUpdate(u.optString("name", ""), u.optString("abbrev", ""),
          u.optString("installed", ""), u.optString("available", "")));
    }
    return new UpdateCheck(o.optString("registry", ""), updates);
  }

  /** Builds a {@code multipart/form-data} body with one file part. */
  private static byte[] multipartFile(String boundary, String field, String filename, byte[] data) {
    String head = "--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
        + "Content-Type: application/octet-stream\r\n\r\n";
    String tail = "\r\n--" + boundary + "--\r\n";
    byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
    byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[headBytes.length + data.length + tailBytes.length];
    System.arraycopy(headBytes, 0, out, 0, headBytes.length);
    System.arraycopy(data, 0, out, headBytes.length, data.length);
    System.arraycopy(tailBytes, 0, out, headBytes.length + data.length, tailBytes.length);
    return out;
  }

  /** A package offered by a registry's catalog (from {@code <registry>/public/apps.xml}). */
  public record AvailablePackage(String abbrev, String name, String title, String version,
      String author) {
  }

  /**
   * Lists the packages a registry offers, by fetching its public catalog
   * ({@code <registryBaseUrl>/public/apps.xml}) directly. This is a read-only call to the (public)
   * registry, not to the eXist server; install still goes through {@link #installPackage}.
   */
  public List<AvailablePackage> listAvailablePackages(String registryBaseUrl)
      throws IOException, InterruptedException {
    String base = registryBaseUrl.endsWith("/")
        ? registryBaseUrl.substring(0, registryBaseUrl.length() - 1) : registryBaseUrl;
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(base + "/public/apps.xml"))
        .timeout(Duration.ofSeconds(60))
        .header("Accept", "application/xml")
        .GET()
        .build();
    HttpResponse<String> resp = sendPrivileged(req);
    int code = resp.statusCode();
    if (code < 200 || code >= 300) {
      throw new ExistHttpException(code, "GET " + req.uri(), resp.body());
    }
    return parseAvailablePackages(resp.body());
  }

  /** Parses a registry {@code apps.xml} catalog into {@link AvailablePackage} entries. */
  static List<AvailablePackage> parseAvailablePackages(String xml) throws IOException {
    List<AvailablePackage> out = new ArrayList<>();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setNamespaceAware(false);
      Document doc = factory.newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      NodeList apps = doc.getElementsByTagName("app");
      for (int i = 0; i < apps.getLength(); i++) {
        if (apps.item(i) instanceof Element app) {
          out.add(new AvailablePackage(
              childText(app, "abbrev"), childText(app, "name"), childText(app, "title"),
              childText(app, "version"), childText(app, "author")));
        }
      }
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Could not parse the registry catalog", e);
    }
    return out;
  }

  private static String childText(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
  }

  // ---------------------------------------------------------------------------
  // Query execution (cursor-based)
  // ---------------------------------------------------------------------------

  /**
   * A server-side query cursor with the total item count and the server's timing breakdown (all in
   * milliseconds): {@code compileMs} + {@code evalMs} ≈ {@code totalMs}. Older servers that don't
   * report timings yield zeros.
   */
  public record QueryHandle(String cursor, int items, int compileMs, int evalMs, int totalMs) {
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
    JSONObject timing = o.optJSONObject("timing");
    int compile = timing != null ? timing.optInt("compile", 0) : 0;
    int eval = timing != null ? timing.optInt("evaluate", 0) : 0;
    int total = timing != null ? timing.optInt("total", o.optInt("elapsed", 0)) : o.optInt("elapsed", 0);
    return new QueryHandle(o.optString("cursor", null), o.optInt("items", 0), compile, eval, total);
  }

  /**
   * GET /api/query/{id}/results — returns the raw JSON array body for a page of results.
   * Each element is an object with at least {@code value} and {@code type}.
   */
  public String fetchResultsRaw(String cursor, int start, int count, String method)
      throws IOException, InterruptedException {
    return fetchResultsRaw(cursor, start, count, method, true);
  }

  /**
   * As {@link #fetchResultsRaw(String, int, int, String)}, controlling pretty-printing via the
   * {@code indent} serialization parameter.
   */
  public String fetchResultsRaw(String cursor, int start, int count, String method, boolean indent)
      throws IOException, InterruptedException {
    String path = "/query/" + enc(cursor) + "/results"
        + "?start=" + start + "&count=" + count + "&method=" + enc(method)
        + "&indent=" + (indent ? "yes" : "no");
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
  // Search (existdb-openapi /api/search — sitewide full-text)
  // ---------------------------------------------------------------------------

  /** One full-text search hit: the owning app, a title, a highlighted snippet, and the DB path. */
  public record SearchHit(String app, String title, String snippet, String path) {
  }

  /** A page of search results plus the total match count reported by the server. */
  public record SearchResults(int total, List<SearchHit> hits) {
  }

  /**
   * GET /api/search — sitewide full-text search. Returns up to {@code limit} hits (with snippets and
   * DB paths) plus the total match count.
   */
  public SearchResults search(String query, int limit) throws IOException, InterruptedException {
    HttpResponse<String> r =
        send(request("/search?q=" + enc(query) + "&limit=" + limit).GET().build());
    JSONObject o = new JSONObject(r.body());
    JSONArray arr = o.optJSONArray("results");
    List<SearchHit> hits = new ArrayList<>();
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        JSONObject h = arr.getJSONObject(i);
        hits.add(new SearchHit(h.optString("app", ""), h.optString("title", ""),
            h.optString("snippet", ""), h.optString("path", "")));
      }
    }
    return new SearchResults(o.optInt("total", hits.size()), hits);
  }

  // ---------------------------------------------------------------------------
  // Language services (existdb-openapi /api/langservice/*, LSP-isomorphic shapes)
  // ---------------------------------------------------------------------------

  /** A diagnostic. {@code line}/{@code column} are 0-based; {@code severity} is 1=error … 4=hint. */
  public record Diagnostic(int line, int column, int severity, String code, String message) {
  }

  /**
   * A completion proposal. {@code kind} is an LSP {@code CompletionItemKind}; {@code filterText} is
   * what the client matches the typed token against (the local name, so bare {@code cou} matches
   * {@code fn:count}); {@code sortText} orders the list. Servers without existdb-openapi #42 omit the
   * latter two, so they fall back to the label's local name and the label respectively.
   */
  public record Completion(String label, int kind, String detail, String documentation,
      String insertText, String filterText, String sortText, int insertTextFormat) {

    /** Whether {@code insertText} is an LSP snippet ({@code insertTextFormat == 2}, with tab stops). */
    public boolean isSnippet() {
      return insertTextFormat == 2;
    }
  }

  /** Hover info for a position: the symbol's documentation as LSP {@code MarkupContent} Markdown. */
  public record Hover(String contents) {
  }

  /** LSP {@code SignatureHelp}: the candidate signatures and which signature/parameter is active. */
  public record SignatureHelp(List<SignatureInfo> signatures, int activeSignature,
      int activeParameter) {
  }

  /** One signature: its full label, Markdown documentation, and ordered parameters. */
  public record SignatureInfo(String label, String documentation, List<ParameterInfo> parameters) {
  }

  /** One parameter of a signature: its label (e.g. {@code $arg as xs:string}) and Markdown docs. */
  public record ParameterInfo(String label, String documentation) {
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
      String label = o.optString("label", "");
      out.add(new Completion(label, o.optInt("kind", 0),
          o.optString("detail", null), o.optString("documentation", null),
          o.optString("insertText", label),
          o.optString("filterText", localName(label)),
          o.optString("sortText", label),
          o.optInt("insertTextFormat", 1)));
    }
    return out;
  }

  /** The local name of a completion label: drops any namespace prefix and {@code #arity} suffix. */
  private static String localName(String label) {
    String name = label;
    int colon = name.lastIndexOf(':');
    if (colon >= 0) {
      name = name.substring(colon + 1);
    }
    int hash = name.indexOf('#');
    return hash >= 0 ? name.substring(0, hash) : name;
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
    // LSP MarkupContent (existdb-openapi PR #44): contents is {kind: "markdown", value: "…"}.
    JSONObject markup = o.optJSONObject("contents");
    if (markup == null) {
      return null;
    }
    String value = markup.optString("value", "");
    return value.isEmpty() ? null : new Hover(value);
  }

  /**
   * POST /api/langservice/signature-help — the signature(s) for the call enclosing the cursor, with
   * the active parameter index (existdb-openapi PR #44). Returns {@code null} when the cursor isn't
   * inside a function call's argument list (the server replies with {@code null}).
   */
  public SignatureHelp signatureHelp(String expression, int line, int column, String moduleLoadPath)
      throws IOException, InterruptedException {
    JSONObject body = langBody(expression, moduleLoadPath);
    body.put("line", line);
    body.put("column", column);
    JSONObject o = asObject(postLang("/langservice/signature-help", body));
    if (o == null) {
      return null;
    }
    JSONArray sigs = o.optJSONArray("signatures");
    if (sigs == null || sigs.isEmpty()) {
      return null;
    }
    List<SignatureInfo> signatures = new ArrayList<>(sigs.length());
    for (int i = 0; i < sigs.length(); i++) {
      JSONObject sig = sigs.getJSONObject(i);
      JSONArray params = sig.optJSONArray("parameters");
      List<ParameterInfo> parameters = new ArrayList<>(params == null ? 0 : params.length());
      for (int j = 0; params != null && j < params.length(); j++) {
        JSONObject p = params.getJSONObject(j);
        parameters.add(new ParameterInfo(p.optString("label", ""), markupValue(p.opt("documentation"))));
      }
      signatures.add(new SignatureInfo(sig.optString("label", ""),
          markupValue(sig.opt("documentation")), parameters));
    }
    return new SignatureHelp(signatures, o.optInt("activeSignature", 0),
        o.optInt("activeParameter", 0));
  }

  /** The text of an LSP documentation field, which is either a {@code MarkupContent} or a string. */
  private static String markupValue(Object value) {
    if (value instanceof JSONObject markup) {
      return markup.optString("value", "");
    }
    return value == null || JSONObject.NULL.equals(value) ? "" : value.toString();
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

  /** Encodes a DB path for a path-in-URL endpoint: per-segment, preserving slashes (space → %20). */
  private static String encPath(String dbPath) {
    String path = dbPath.startsWith("/") ? dbPath.substring(1) : dbPath;
    StringBuilder out = new StringBuilder();
    for (String segment : path.split("/")) {
      if (out.length() > 0) {
        out.append('/');
      }
      out.append(enc(segment).replace("+", "%20"));
    }
    return out.toString();
  }

  private HttpResponse<byte[]> sendBytes(HttpRequest req) throws IOException, InterruptedException {
    try {
      return java.security.AccessController.doPrivileged(
          (java.security.PrivilegedExceptionAction<HttpResponse<byte[]>>) () ->
              http.send(req, HttpResponse.BodyHandlers.ofByteArray()));
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
}
