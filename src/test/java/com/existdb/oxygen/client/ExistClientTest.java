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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ExistClient} against a canned existdb-openapi mock (no eXist, no network). */
class ExistClientTest {

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
  void systemInfoReturnsBody() throws Exception {
    assertTrue(client.systemInfo().contains("7.0.0"));
  }

  @Test
  void whoamiUserReadsNestedEffectiveUser() throws Exception {
    // The response nests identity under effective/real; whoamiUser() must dig it out.
    assertEquals("admin", client.whoamiUser());
  }

  @Test
  void listChildrenParsesCollectionChildren() throws Exception {
    List<ExistClient.ChildEntry> children = client.listChildren("/db");
    assertEquals(2, children.size());
    assertTrue(children.get(0).collection());
    assertEquals("apps", children.get(0).name());
    assertEquals("/db/apps", children.get(0).path());
    assertFalse(children.get(1).collection());
    assertEquals("index.xq", children.get(1).name());
  }

  @Test
  void getResourceParsesContentAndMime() throws Exception {
    ExistClient.ResourceContent rc = client.getResource("/db/x.xq");
    assertTrue(rc.content().contains("42"));
    assertEquals("application/xquery", rc.mimeType());
    assertFalse(rc.binary());
  }

  @Test
  void putResourceSendsPathAndContent() throws Exception {
    client.putResource("/db/x.xq", "xquery version \"3.1\"; 99", "application/xquery");
    assertTrue(server.lastPutBody().contains("\"path\""));
    assertTrue(server.lastPutBody().contains("99"));
  }

  @Test
  void runAndFetchAndCloseQuery() throws Exception {
    ExistClient.QueryHandle handle = client.runQuery("(1 to 3)", null);
    assertEquals("C1", handle.cursor());
    assertEquals(3, handle.items());
    assertTrue(server.lastQueryBody().contains("1 to 3"));

    String page = client.fetchResultsRaw(handle.cursor(), 1, 10, "adaptive");
    assertTrue(page.trim().startsWith("["));
    assertTrue(page.contains("\"3\""));

    client.closeCursor(handle.cursor()); // must not throw
  }

  @Test
  void nonSuccessStatusRaisesExistHttpException() {
    ExistHttpException ex = org.junit.jupiter.api.Assertions.assertThrows(
        ExistHttpException.class, () -> client.getResource("/db/missing.xq"));
    assertEquals(404, ex.getStatusCode());
    assertTrue(ex.getResponseBody().contains("not found"));
  }

  @Test
  void existUrlRoundTripReadsAndWrites() throws Exception {
    // The exist: URL handler reads via GET and saves via PUT-on-close (Oxygen's native save path).
    ExistContext.setActiveProfile(
        new ConnectionProfile("test", server.baseUrl(), "admin", "secret"));
    URL url = ExistURLStreamHandler.toUrl("/db/x.xq");

    String read = new String(url.openConnection().getInputStream().readAllBytes(),
        StandardCharsets.UTF_8);
    assertTrue(read.contains("42"));

    URLConnection writeConn = url.openConnection();
    try (OutputStream os = writeConn.getOutputStream()) {
      os.write("xquery version \"3.1\"; 123".getBytes(StandardCharsets.UTF_8));
    }
    assertTrue(server.lastPutBody().contains("123"));
  }
}
