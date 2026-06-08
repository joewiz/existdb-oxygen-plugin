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
package com.existdb.oxygen.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ProjectConnection} discovery + .existdb.json/.env precedence. */
class ProjectConnectionTest {

  private static final String DESCRIPTOR = """
      { "servers": { "s": { "server": "http://json:8080/exist" } }, "sync": { "server": "s" } }
      """;

  @Test
  void readsServerFromExistdbJson(@TempDir Path dir) throws Exception {
    write(dir, ".existdb.json", DESCRIPTOR);
    assertEquals("http://json:8080/exist",
        ProjectConnection.resolve(dir.toFile(), null).orElseThrow().serverRoot());
  }

  @Test
  void readsServerFromEnv(@TempDir Path dir) throws Exception {
    write(dir, ".env", "export EXISTDB_SERVER=\"http://env:8443/exist\"\nEXISTDB_USER=admin\n");
    ProjectConnection.Resolved resolved = ProjectConnection.resolve(dir.toFile(), null).orElseThrow();
    assertEquals("http://env:8443/exist", resolved.serverRoot());
    assertEquals(".env", resolved.source());
  }

  @Test
  void existdbJsonWinsOverEnvInSameDir(@TempDir Path dir) throws Exception {
    write(dir, ".existdb.json", DESCRIPTOR);
    write(dir, ".env", "EXISTDB_SERVER=http://env:8443/exist\n");
    assertEquals("http://json:8080/exist",
        ProjectConnection.resolve(dir.toFile(), null).orElseThrow().serverRoot());
  }

  @Test
  void closestFileWinsAcrossLevels(@TempDir Path projectRoot) throws Exception {
    // .existdb.json at the parent, .env closer (in the repo) — the closer .env governs.
    write(projectRoot, ".existdb.json", DESCRIPTOR);
    Path repo = Files.createDirectories(projectRoot.resolve("repo"));
    write(repo, ".env", "EXISTDB_SERVER=http://env:8443/exist\n");
    File nested = write(Files.createDirectories(repo.resolve("modules")), "x.xqm", "1");
    assertEquals("http://env:8443/exist",
        ProjectConnection.resolve(nested, projectRoot.toFile()).orElseThrow().serverRoot());
  }

  @Test
  void envIgnoresCommentsAndOtherKeys(@TempDir Path dir) throws Exception {
    write(dir, ".env", "# EXISTDB_SERVER=http://commented-out/exist\nEXISTDB_USER=admin\n"
        + "EXISTDB_SERVER=http://real:8080/exist\n");
    assertEquals("http://real:8080/exist", ProjectConnection.serverFromEnv(dir.resolve(".env").toFile()));
  }

  @Test
  void noConfigFound(@TempDir Path dir) throws Exception {
    File file = write(dir, "data.xml", "<x/>");
    assertTrue(ProjectConnection.resolve(file, dir.toFile()).isEmpty());
  }

  private static File write(Path dir, String name, String content) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file.toFile();
  }
}
