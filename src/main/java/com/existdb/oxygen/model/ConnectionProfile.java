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
package com.existdb.oxygen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single eXist-db connection: a base URL plus credentials.
 *
 * <p>The base URL is the eXist <em>server root</em> (servlet context), e.g.
 * {@code http://localhost:8080/exist}. The existdb-openapi application path
 * ({@code /apps/existdb-openapi}) and its {@code /api/*} endpoints are inferred from there by
 * {@link #getApiRoot()} — the package installs at a fixed path, so the API is always at
 * {@code <root>/apps/existdb-openapi/api}. A non-standard install can be reached by entering the
 * full application URL (e.g. {@code http://host/exist/apps/my-openapi}), which is honored verbatim
 * as an override; {@link #normalizeBaseUrl} collapses only the standard path back to the short
 * form.</p>
 */
public final class ConnectionProfile {

  /** The application path existdb-openapi installs at; inferred when the base URL omits it. */
  private static final String STANDARD_APP_PATH = "/apps/existdb-openapi";

  /**
   * The profile's identifier — a URL-safe slug of its name (e.g. {@code localhost-8080}), assigned
   * by {@code ProfileStore}. It appears in {@code exist://<id>/…} URLs, so it's chosen to be
   * human-meaningful in editor titles. On rename it changes and the old slug is kept in
   * {@link #aliases} so already-open editors keep resolving. May be {@code null} until the store
   * assigns one.
   */
  private String id;
  /** Prior ids (slugs) this profile used, kept so {@code exist://<oldSlug>/…} URLs still route. */
  private final List<String> aliases = new ArrayList<>();
  private String name;
  private String baseUrl;
  private String user;
  private String password;
  private boolean acceptSelfSigned;

  public ConnectionProfile() {
    this("localhost 8080", "http://localhost:8080/exist", "admin", "", false);
  }

  public ConnectionProfile(String name, String baseUrl, String user, String password) {
    this(name, baseUrl, user, password, false);
  }

  public ConnectionProfile(String name, String baseUrl, String user, String password,
      boolean acceptSelfSigned) {
    this.name = name;
    this.baseUrl = baseUrl;
    this.user = user;
    this.password = password;
    this.acceptSelfSigned = acceptSelfSigned;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  /** Prior slugs this profile used (so old {@code exist://} URLs still route after a rename). */
  public List<String> getAliases() {
    return aliases;
  }

  public void setAliases(List<String> values) {
    aliases.clear();
    if (values != null) {
      values.forEach(this::addAlias);
    }
  }

  /** Records a previous id as an alias (ignoring blanks, the current id, and duplicates). */
  public void addAlias(String alias) {
    if (alias != null && !alias.isBlank() && !alias.equals(id) && !aliases.contains(alias)) {
      aliases.add(alias);
    }
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  /**
   * Whether to trust self-signed / otherwise-unverified TLS certificates when the base URL is
   * {@code https}. eXist's default HTTPS listener ships a self-signed cert and {@code xst} defaults
   * to HTTPS, so dev setups commonly need this; leave it off for production servers with a
   * CA-signed certificate. Ignored for plain {@code http}.
   */
  public boolean isAcceptSelfSigned() {
    return acceptSelfSigned;
  }

  public void setAcceptSelfSigned(boolean acceptSelfSigned) {
    this.acceptSelfSigned = acceptSelfSigned;
  }

  /**
   * The existdb-openapi {@code /api} root the plugin talks to. The application path is inferred from
   * a bare server root (e.g. {@code http://host/exist} → {@code http://host/exist/apps/existdb-openapi/api}),
   * while a base URL that already carries an application path — the standard one or a custom override
   * like {@code /apps/my-openapi} — is honored as-is. Trailing slashes are normalized away.
   */
  public String getApiRoot() {
    String base = normalizedBase();
    if (base.endsWith("/api")) {
      return base;
    }
    return base.contains("/apps/") ? base + "/api" : base + STANDARD_APP_PATH + "/api";
  }

  /**
   * The eXist server root (servlet context, e.g. {@code http://localhost:8080/exist}) — the base URL
   * with any trailing application path ({@code /apps/<abbrev>}) removed. This is what {@code xst} /
   * node-exist connect to over REST/XML-RPC, as opposed to the openapi {@code /api} surface this
   * plugin itself uses. With the short base-URL form this is just the base URL.
   */
  public String getServerRoot() {
    String base = normalizedBase();
    int apps = base.lastIndexOf("/apps/");
    if (apps >= 0 && base.indexOf('/', apps + "/apps/".length()) < 0) {
      return base.substring(0, apps);
    }
    return base;
  }

  /**
   * Normalizes a user-entered or stored base URL to the short eXist server-root form for display and
   * storage: trims, drops trailing slashes and a trailing {@code /api}, and collapses the standard
   * {@code /apps/existdb-openapi} application path away. A non-standard application path is preserved
   * as an explicit override. The result yields the same {@link #getApiRoot()} either way, so the
   * normalization is lossless with respect to where requests are sent.
   */
  public static String normalizeBaseUrl(String url) {
    if (url == null) {
      return null;
    }
    String base = stripTrailingSlashes(url.trim());
    if (base.endsWith("/api")) {
      base = stripTrailingSlashes(base.substring(0, base.length() - "/api".length()));
    }
    if (base.endsWith(STANDARD_APP_PATH)) {
      base = base.substring(0, base.length() - STANDARD_APP_PATH.length());
    }
    return base;
  }

  private String normalizedBase() {
    return stripTrailingSlashes(baseUrl == null ? "" : baseUrl.trim());
  }

  private static String stripTrailingSlashes(String s) {
    String base = s;
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }
}
