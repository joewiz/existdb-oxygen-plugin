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
package com.existdb.oxygen.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.client.MockExistServer;
import com.existdb.oxygen.model.ConnectionProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link QueryRunner} against the existdb-openapi mock (no eXist, no network). */
class QueryRunnerTest {

  private MockExistServer server;
  private ExistClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockExistServer();
    client = new ExistClient(new ConnectionProfile("test", server.baseUrl(), "admin", "secret"));
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void executeSerializesValuesOnePerLine() throws Exception {
    QueryRunner.QueryResult result = QueryRunner.execute(client, "(1 to 3)", null);
    assertEquals("1\n2\n3", result.output());
    assertEquals(3, result.totalItems());
    assertFalse(result.truncated());
    assertEquals(3, result.items().size());
    assertEquals("2", result.items().get(1).value());
  }

  @Test
  void looksLikeXmlDetectsLeadingAngleBracket() {
    assertTrue(QueryRunner.looksLikeXml("  <result>1</result>"));
    assertFalse(QueryRunner.looksLikeXml("42"));
    assertFalse(QueryRunner.looksLikeXml(null));
  }
}
