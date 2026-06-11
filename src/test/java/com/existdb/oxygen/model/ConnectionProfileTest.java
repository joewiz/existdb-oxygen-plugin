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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConnectionProfile} URL normalization and defaults. */
class ConnectionProfileTest {

  @Test
  void apiRootInfersOpenapiPathFromShortServerRoot() {
    ConnectionProfile p = new ConnectionProfile("x", "http://localhost:8080/exist", "admin", "");
    assertEquals("http://localhost:8080/exist/apps/existdb-openapi/api", p.getApiRoot());
  }

  @Test
  void apiRootHonorsAFullStandardApplicationUrl() {
    ConnectionProfile p = new ConnectionProfile(
        "x", "http://localhost:8080/exist/apps/existdb-openapi", "admin", "");
    assertEquals("http://localhost:8080/exist/apps/existdb-openapi/api", p.getApiRoot());
  }

  @Test
  void apiRootHonorsACustomApplicationPathOverride() {
    ConnectionProfile p = new ConnectionProfile(
        "x", "http://host/exist/apps/my-openapi", "admin", "");
    assertEquals("http://host/exist/apps/my-openapi/api", p.getApiRoot());
  }

  @Test
  void apiRootStripsTrailingSlashes() {
    ConnectionProfile p = new ConnectionProfile("x", "http://localhost:8080/exist///", "admin", "");
    assertEquals("http://localhost:8080/exist/apps/existdb-openapi/api", p.getApiRoot());
  }

  @Test
  void apiRootTrimsWhitespace() {
    ConnectionProfile p = new ConnectionProfile("x", "  http://h:8080/exist  ", "admin", "");
    assertEquals("http://h:8080/exist/apps/existdb-openapi/api", p.getApiRoot());
  }

  @Test
  void serverRootStripsStandardAndCustomApplicationPaths() {
    assertEquals("http://h/exist",
        new ConnectionProfile("x", "http://h/exist", "admin", "").getServerRoot());
    assertEquals("http://h/exist",
        new ConnectionProfile("x", "http://h/exist/apps/existdb-openapi", "admin", "")
            .getServerRoot());
    assertEquals("http://h/exist",
        new ConnectionProfile("x", "http://h/exist/apps/my-openapi", "admin", "").getServerRoot());
  }

  @Test
  void normalizeBaseUrlCollapsesStandardPathButKeepsOverrides() {
    assertEquals("http://h/exist", ConnectionProfile.normalizeBaseUrl("http://h/exist"));
    assertEquals("http://h/exist",
        ConnectionProfile.normalizeBaseUrl("http://h/exist/apps/existdb-openapi/"));
    assertEquals("http://h/exist",
        ConnectionProfile.normalizeBaseUrl("  http://h/exist/apps/existdb-openapi/api  "));
    assertEquals("http://h/exist/apps/my-openapi",
        ConnectionProfile.normalizeBaseUrl("http://h/exist/apps/my-openapi"));
  }

  @Test
  void acceptSelfSignedDefaultsToFalse() {
    assertFalse(new ConnectionProfile().isAcceptSelfSigned());
    assertFalse(new ConnectionProfile("x", "http://h/x", "admin", "").isAcceptSelfSigned());
  }

  @Test
  void acceptSelfSignedRetainedFromConstructor() {
    ConnectionProfile p = new ConnectionProfile("x", "https://h/x", "admin", "", true);
    assertTrue(p.isAcceptSelfSigned());
  }

  @Test
  void acceptSelfSignedSettable() {
    ConnectionProfile p = new ConnectionProfile();
    p.setAcceptSelfSigned(true);
    assertTrue(p.isAcceptSelfSigned());
  }
}
