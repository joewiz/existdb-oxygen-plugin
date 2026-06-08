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
 * <p>The base URL points at the existdb-openapi application root, e.g.
 * {@code http://localhost:8080/exist/apps/existdb-openapi}. The {@code /api/*} endpoints
 * hang off that root.</p>
 */
public final class ConnectionProfile {

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
    this("localhost 8080", "http://localhost:8080/exist/apps/existdb-openapi", "admin", "", false);
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

  /** The {@code /api} root, with any trailing slash on the base URL normalized away. */
  public String getApiRoot() {
    return normalizedBase() + "/api";
  }

  /**
   * The eXist server root (servlet context, e.g. {@code http://localhost:8080/exist}) — the base URL
   * with the {@code /apps/existdb-openapi} application path removed. This is what {@code xst} /
   * node-exist connect to over REST/XML-RPC, as opposed to the openapi {@code /api} surface this
   * plugin itself uses.
   */
  public String getServerRoot() {
    String base = normalizedBase();
    String appPath = "/apps/existdb-openapi";
    return base.endsWith(appPath) ? base.substring(0, base.length() - appPath.length()) : base;
  }

  private String normalizedBase() {
    String base = baseUrl == null ? "" : baseUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }
}
