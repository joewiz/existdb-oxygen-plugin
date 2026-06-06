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

import com.existdb.oxygen.model.ConnectionProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the shared {@link Uploads} helpers against the existdb-openapi mock. */
class UploadsTest {

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
  void isBinaryDetectsNulByte() {
    assertTrue(Uploads.isBinary(new byte[] {1, 2, 0, 3}));
    assertFalse(Uploads.isBinary("plain text".getBytes(StandardCharsets.UTF_8)));
    assertFalse(Uploads.isBinary(new byte[0]));
  }

  @Test
  void uploadRecursiveStoresASingleFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("data.xml");
    Files.writeString(file, "<doc/>");
    int count = Uploads.uploadRecursive(client, "/db/target", file.toFile(), true);
    assertEquals(1, count);
    assertTrue(server.lastPutBody().contains("/db/target/data.xml"));
  }

  @Test
  void uploadRecursiveWalksADirectoryTree(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("a.xqm"), "xquery version \"3.1\"; 1");
    Path sub = Files.createDirectories(dir.resolve("modules"));
    Files.writeString(sub.resolve("b.xml"), "<b/>");
    // Two resources stored (the directories themselves don't count toward the file total).
    int count = Uploads.uploadRecursive(client, "/db/target", dir.toFile(), true);
    assertEquals(2, count);
  }

  @Test
  void uploadRecursiveSkipsHiddenWhenExcluded(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("keep.xml"), "<k/>");
    Files.writeString(dir.resolve(".DS_Store"), "junk");
    Path git = Files.createDirectories(dir.resolve(".git"));
    Files.writeString(git.resolve("config"), "stuff");
    // Only keep.xml is uploaded; .DS_Store and the .git directory are skipped.
    assertEquals(1, Uploads.uploadRecursive(client, "/db/target", dir.toFile(), false));
    // With includeHidden=true, the hidden file and the .git/config are stored too.
    assertEquals(3, Uploads.uploadRecursive(client, "/db/target", dir.toFile(), true));
  }

  @Test
  void isHiddenMatchesDotPrefix() {
    assertTrue(Uploads.isHidden(new java.io.File(".git")));
    assertFalse(Uploads.isHidden(new java.io.File("data.xml")));
  }
}
