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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BuildConfig} parsing, auto-detection, and closest-ancestor discovery. */
class BuildConfigTest {

  @Test
  void explicitBuildSectionWins(@TempDir Path dir) throws Exception {
    write(dir, ".existdb.json", """
        { "servers": { "s": { "server": "http://h:8080/exist" } },
          "build": { "tool": "ant", "command": "ant xar", "artifact": "build/*.xar" } }
        """);
    write(dir, "build.xml", "<project/>"); // marker present too, but explicit section wins
    BuildConfig config = BuildConfig.findNearest(dir.toFile(), null).orElseThrow();
    assertEquals(BuildConfig.Tool.ANT, config.tool());
    assertEquals("ant xar", config.command());
    assertEquals(dir.toFile(), config.dir());
  }

  @Test
  void commandDefaultsFromTool(@TempDir Path dir) throws Exception {
    write(dir, ".existdb.json", """
        { "build": { "tool": "maven" } }
        """);
    BuildConfig config = BuildConfig.findNearest(dir.toFile(), null).orElseThrow();
    assertEquals(BuildConfig.Tool.MAVEN, config.tool());
    assertEquals("mvn package", config.command());
  }

  @Test
  void autoDetectsAntFromBuildXml(@TempDir Path dir) throws Exception {
    write(dir, "build.xml", "<project default=\"xar\"/>");
    BuildConfig config = BuildConfig.findNearest(dir.toFile(), null).orElseThrow();
    assertEquals(BuildConfig.Tool.ANT, config.tool());
    assertEquals("ant", config.command());
  }

  @Test
  void autoDetectsNpmFromPackageJson(@TempDir Path dir) throws Exception {
    write(dir, "package.json", "{}");
    BuildConfig config = BuildConfig.findNearest(dir.toFile(), null).orElseThrow();
    assertEquals(BuildConfig.Tool.NPM, config.tool());
    assertEquals("npm run build", config.command());
  }

  @Test
  void antMarkerTakesPrecedenceOverMaven(@TempDir Path dir) throws Exception {
    write(dir, "build.xml", "<project/>");
    write(dir, "pom.xml", "<project/>");
    assertEquals(BuildConfig.Tool.ANT,
        BuildConfig.findNearest(dir.toFile(), null).orElseThrow().tool());
  }

  @Test
  void underspecifiedBuildSectionFallsBackToMarker(@TempDir Path dir) throws Exception {
    write(dir, ".existdb.json", "{ \"build\": { } }");
    write(dir, "pom.xml", "<project/>");
    assertEquals(BuildConfig.Tool.MAVEN,
        BuildConfig.findNearest(dir.toFile(), null).orElseThrow().tool());
  }

  @Test
  void findsNearestBuildRootByAncestorWalk(@TempDir Path projectRoot) throws Exception {
    Path repo = Files.createDirectories(projectRoot.resolve("repos/app1"));
    write(repo, "build.xml", "<project/>");
    File nested = write(Files.createDirectories(repo.resolve("modules")), "x.xqm", "1");
    BuildConfig config = BuildConfig.findNearest(nested, projectRoot.toFile()).orElseThrow();
    assertEquals(repo.toFile(), config.dir());
  }

  @Test
  void noBuildRootFound(@TempDir Path dir) throws Exception {
    File file = write(dir, "data.xml", "<x/>");
    assertTrue(BuildConfig.findNearest(file, dir.toFile()).isEmpty());
  }

  @Test
  void locateArtifactFindsNewestXar(@TempDir Path dir) throws Exception {
    write(dir, "build.xml", "<project/>");
    Path build = Files.createDirectories(dir.resolve("build"));
    File older = write(build, "app-0.9.xar", "old");
    older.setLastModified(1_000L);
    File newer = write(build, "app-1.0.xar", "new");
    newer.setLastModified(2_000_000L);
    Optional<File> found = BuildConfig.findNearest(dir.toFile(), null).orElseThrow().locateArtifact();
    assertEquals(newer, found.orElseThrow());
  }

  private static File write(Path dir, String name, String content) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file.toFile();
  }
}
