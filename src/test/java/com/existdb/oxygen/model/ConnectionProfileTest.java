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

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConnectionProfile} URL normalization. */
class ConnectionProfileTest {

  @Test
  void apiRootAppendsApiToBaseUrl() {
    ConnectionProfile p = new ConnectionProfile(
        "x", "http://localhost:8080/exist/apps/existdb-openapi", "admin", "");
    assertEquals("http://localhost:8080/exist/apps/existdb-openapi/api", p.getApiRoot());
  }

  @Test
  void apiRootStripsTrailingSlashes() {
    ConnectionProfile p = new ConnectionProfile(
        "x", "http://localhost:8080/exist/apps/existdb-openapi///", "admin", "");
    assertEquals("http://localhost:8080/exist/apps/existdb-openapi/api", p.getApiRoot());
  }

  @Test
  void apiRootTrimsWhitespace() {
    ConnectionProfile p = new ConnectionProfile("x", "  http://h:8080/x  ", "admin", "");
    assertEquals("http://h:8080/x/api", p.getApiRoot());
  }
}
