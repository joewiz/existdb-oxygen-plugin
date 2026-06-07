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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ExistdbProjectConfig} parsing and closest-ancestor discovery. */
class ExistdbProjectConfigTest {

  private static final String FULL_CONFIG = """
      {
        "servers": {
          "local": { "server": "http://localhost:8080/exist", "user": "admin",
                     "root": "/db/apps/myapp" },
          "prod":  { "server": "https://example.org/exist", "user": "deploy" }
        },
        "sync": { "server": "local", "root": "/db/apps/myapp",
                  "ignore": ["target/**", ".git/**"] }
      }
      """;

  @Test
  void parsesServerTargetAndIgnore(@TempDir Path dir) throws Exception {
    File descriptor = write(dir, ExistdbProjectConfig.FILE_NAME, FULL_CONFIG);
    ExistdbProjectConfig config = ExistdbProjectConfig.parse(descriptor);
    assertEquals("http://localhost:8080/exist", config.serverUrl());
    assertEquals("admin", config.user());
    assertEquals("/db/apps/myapp", config.targetCollection());
    assertEquals(2, config.ignore().size());
    assertTrue(config.ignore().contains("target/**"));
    assertEquals(dir.toFile(), config.descriptorDir());
    assertFalse(config.uploadOnSave()); // off unless sync.onSave is explicitly set
  }

  @Test
  void readsUploadOnSaveFlag(@TempDir Path dir) throws Exception {
    String json = """
        { "servers": { "s": { "server": "http://h:8080/exist", "root": "/db/x" } },
          "sync": { "server": "s", "onSave": true } }
        """;
    ExistdbProjectConfig config = ExistdbProjectConfig.parse(write(dir, ".existdb.json", json));
    assertTrue(config.uploadOnSave());
  }

  @Test
  void fallsBackToFirstServerWhenSyncAbsent(@TempDir Path dir) throws Exception {
    String json = """
        { "servers": { "only": { "server": "http://h:8080/exist", "root": "/db/x" } } }
        """;
    File descriptor = write(dir, ExistdbProjectConfig.FILE_NAME, json);
    ExistdbProjectConfig config = ExistdbProjectConfig.parse(descriptor);
    assertEquals("http://h:8080/exist", config.serverUrl());
    assertEquals("/db/x", config.targetCollection());
    assertTrue(config.ignore().isEmpty());
  }

  @Test
  void targetIsNullWhenNoRootGiven(@TempDir Path dir) throws Exception {
    String json = """
        { "servers": { "s": { "server": "http://h:8080/exist" } }, "sync": { "server": "s" } }
        """;
    ExistdbProjectConfig config = ExistdbProjectConfig.parse(write(dir, ".existdb.json", json));
    assertNull(config.targetCollection());
  }

  @Test
  void findsNearestDescriptorByAncestorWalk(@TempDir Path projectRoot) throws Exception {
    // projectRoot/repos/app1/.existdb.json + a file nested under it.
    Path app1 = Files.createDirectories(projectRoot.resolve("repos/app1/modules"));
    write(projectRoot.resolve("repos/app1"), ".existdb.json", FULL_CONFIG);
    File nested = write(app1, "foo.xqm", "xquery version \"3.1\"; 1");

    Optional<ExistdbProjectConfig> found =
        ExistdbProjectConfig.findNearest(nested, projectRoot.toFile());
    assertTrue(found.isPresent());
    assertEquals(projectRoot.resolve("repos/app1").toFile(), found.get().descriptorDir());
    assertEquals("/db/apps/myapp", found.get().targetCollection());
  }

  @Test
  void siblingRepoWithoutDescriptorFindsNothing(@TempDir Path projectRoot) throws Exception {
    write(projectRoot.resolve("repos/app1"), ".existdb.json", FULL_CONFIG);
    Path app2 = Files.createDirectories(projectRoot.resolve("repos/app2"));
    File file = write(app2, "data.xml", "<x/>");

    assertTrue(ExistdbProjectConfig.findNearest(file, projectRoot.toFile()).isEmpty());
  }

  @Test
  void walkStopsAtProjectRoot(@TempDir Path outer) throws Exception {
    // Descriptor sits ABOVE the project root, so a walk bounded at the root must not find it.
    write(outer, ".existdb.json", FULL_CONFIG);
    Path projectRoot = Files.createDirectories(outer.resolve("project"));
    File file = write(Files.createDirectories(projectRoot.resolve("sub")), "a.xml", "<a/>");

    assertTrue(ExistdbProjectConfig.findNearest(file, projectRoot.toFile()).isEmpty());
  }

  private static File write(Path dir, String name, String content) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file.toFile();
  }
}
