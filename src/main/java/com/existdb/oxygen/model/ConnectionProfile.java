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

/**
 * A single eXist-db connection: a base URL plus credentials.
 *
 * <p>The base URL points at the existdb-openapi application root, e.g.
 * {@code http://localhost:8080/exist/apps/existdb-openapi}. The {@code /api/*} endpoints
 * hang off that root.</p>
 */
public final class ConnectionProfile {

  /**
   * Stable, hidden identifier for this profile, assigned once by {@code ProfileStore}. Survives
   * name and base-URL edits, and identifies the server in {@code exist://<id>/…} URLs. May be
   * {@code null} for a freshly-constructed profile until the store assigns one.
   */
  private String id;
  private String name;
  private String baseUrl;
  private String user;
  private String password;
  private boolean acceptSelfSigned;

  public ConnectionProfile() {
    this("Local eXist", "http://localhost:8080/exist/apps/existdb-openapi", "admin", "", false);
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
    String base = baseUrl == null ? "" : baseUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/api";
  }
}
