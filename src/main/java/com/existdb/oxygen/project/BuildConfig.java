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

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * How to build the eXist-db package rooted at a directory: the build tool, the shell command to run,
 * and where the produced {@code .xar} lands. Resolved either from an explicit {@code "build"} section
 * in the nearest {@code .existdb.json}, or auto-detected from a build marker
 * ({@code build.xml}→Ant, {@code pom.xml}→Maven, {@code package.json}→npm, {@code gulpfile.js}→gulp).
 *
 * <p>The {@code build} section is a plugin extension to the {@code .existdb.json} convention (which
 * standardizes only {@code servers}/{@code sync}); shape:
 * {@code "build": { "tool": "ant|maven|npm|gulp|custom", "command": "ant xar", "artifact": "build/*.xar",
 * "onSave": true, "install": true }}. All keys are optional — {@code command} defaults from
 * {@code tool}, {@code artifact} defaults to scanning for the newest {@code .xar}. {@code onSave}
 * (default false) opts the package into auto-build when a file under it is saved; {@code install}
 * (default false) also installs the built {@code .xar} after a successful auto-build.</p>
 */
public final class BuildConfig {

  /** The recognized build tools (CUSTOM = an explicit command with no specific tool). */
  public enum Tool { ANT, MAVEN, NPM, GULP, CUSTOM }

  private final File dir;
  private final Tool tool;
  private final String command;
  private final String artifactGlob;
  private final boolean autoBuild;
  private final boolean autoInstall;

  private BuildConfig(File dir, Tool tool, String command, String artifactGlob,
      boolean autoBuild, boolean autoInstall) {
    this.dir = dir;
    this.tool = tool;
    this.command = command;
    this.artifactGlob = artifactGlob;
    this.autoBuild = autoBuild;
    this.autoInstall = autoInstall;
  }

  /** Whether this package opts into auto-build on save ({@code build.onSave}). */
  public boolean autoBuild() {
    return autoBuild;
  }

  /** Whether a successful auto-build also installs the {@code .xar} ({@code build.install}). */
  public boolean autoInstall() {
    return autoInstall;
  }

  /** A copy of this config with the auto-build/auto-install flags set. */
  private BuildConfig withFlags(boolean newAutoBuild, boolean newAutoInstall) {
    return new BuildConfig(dir, tool, command, artifactGlob, newAutoBuild, newAutoInstall);
  }

  /** The package/build root directory (the command's working directory). */
  public File dir() {
    return dir;
  }

  public Tool tool() {
    return tool;
  }

  /** The shell command to run (e.g. {@code "ant xar"}, {@code "mvn package"}). */
  public String command() {
    return command;
  }

  /**
   * Walks up from {@code start} (a file or directory) to the nearest build root — the closest
   * ancestor-or-self directory with an explicit {@code .existdb.json} build section or a recognized
   * build marker — bounded at {@code stopAt} (inclusive; may be {@code null} for the filesystem root).
   */
  public static Optional<BuildConfig> findNearest(File start, File stopAt) {
    File dir = start == null ? null : (start.isDirectory() ? start : start.getParentFile());
    while (dir != null) {
      Optional<BuildConfig> here = at(dir);
      if (here.isPresent()) {
        return here;
      }
      if (stopAt != null && dir.equals(stopAt)) {
        break;
      }
      dir = dir.getParentFile();
    }
    return Optional.empty();
  }

  /**
   * The build config rooted exactly at {@code dir}, or empty if it isn't a build root. The
   * command/tool/artifact come from an explicit {@code .existdb.json} build section if it specifies
   * them, otherwise from a build marker; the {@code onSave}/{@code install} flags are read from the
   * build section either way (so an auto-detected build can still opt into auto-build).
   */
  static Optional<BuildConfig> at(File dir) {
    JSONObject build = readBuildSection(dir);
    BuildConfig base = fromBuildSection(dir, build);
    if (base == null) {
      base = detectFromMarker(dir);
    }
    if (base == null) {
      return Optional.empty();
    }
    boolean autoBuild = build != null && build.optBoolean("onSave", false);
    boolean autoInstall = build != null && build.optBoolean("install", false);
    return Optional.of(base.withFlags(autoBuild, autoInstall));
  }

  /** The {@code build} object from {@code dir}'s {@code .existdb.json}, or {@code null} if none/malformed. */
  private static JSONObject readBuildSection(File dir) {
    File descriptor = new File(dir, ExistdbProjectConfig.FILE_NAME);
    if (!descriptor.isFile()) {
      return null;
    }
    try {
      return new JSONObject(Files.readString(descriptor.toPath())).optJSONObject("build");
    } catch (IOException | RuntimeException e) {
      return null; // malformed descriptor — fall back to marker auto-detection
    }
  }

  /** An explicit build config from an already-read build section, or {@code null} if underspecified. */
  private static BuildConfig fromBuildSection(File dir, JSONObject build) {
    if (build == null) {
      return null;
    }
    Tool tool = parseTool(build.optString("tool", null));
    String command = emptyToNull(build.optString("command", null));
    if (tool == null && command == null) {
      return null; // underspecified build section — fall back to marker auto-detection
    }
    if (command == null) {
      command = defaultCommand(tool);
    }
    return new BuildConfig(dir, tool == null ? Tool.CUSTOM : tool, command,
        emptyToNull(build.optString("artifact", null)), false, false);
  }

  /** A build config auto-detected from a build marker in {@code dir}, or {@code null} if none. */
  private static BuildConfig detectFromMarker(File dir) {
    if (new File(dir, "build.xml").isFile()) {
      return detected(dir, Tool.ANT);
    }
    if (new File(dir, "pom.xml").isFile()) {
      return detected(dir, Tool.MAVEN);
    }
    if (new File(dir, "package.json").isFile()) {
      return detected(dir, Tool.NPM);
    }
    if (new File(dir, "gulpfile.js").isFile()) {
      return detected(dir, Tool.GULP);
    }
    return null;
  }

  private static BuildConfig detected(File dir, Tool tool) {
    return new BuildConfig(dir, tool, defaultCommand(tool), defaultArtifact(tool), false, false);
  }

  private static Tool parseTool(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      return Tool.valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return Tool.CUSTOM;
    }
  }

  private static String defaultCommand(Tool tool) {
    return switch (tool) {
      case ANT -> "ant";
      case MAVEN -> "mvn package";
      case NPM -> "npm run build";
      case GULP -> "gulp";
      case CUSTOM -> null;
    };
  }

  private static String defaultArtifact(Tool tool) {
    return switch (tool) {
      case ANT -> "build/*.xar";
      case MAVEN -> "target/*.xar";
      default -> null; // scan for the newest .xar
    };
  }

  /**
   * Locates the built {@code .xar}: the configured artifact glob (relative to {@link #dir}) if set,
   * otherwise the most-recently-modified {@code .xar} under the build root (skipping {@code
   * node_modules}/{@code .git}). Empty if none is found.
   */
  public Optional<File> locateArtifact() {
    Path base = dir.toPath();
    PathMatcher matcher = artifactGlob == null ? null
        : FileSystems.getDefault().getPathMatcher("glob:" + artifactGlob);
    try (Stream<Path> walk = Files.walk(base, 5)) {
      return walk
          .filter(Files::isRegularFile)
          .filter(p -> !isUnderIgnoredDir(base.relativize(p)))
          .filter(p -> matcher != null ? matcher.matches(base.relativize(p))
              : p.getFileName().toString().endsWith(".xar"))
          .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
          .map(Path::toFile);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static boolean isUnderIgnoredDir(Path relative) {
    String rel = relative.toString().replace(File.separatorChar, '/');
    return rel.startsWith("node_modules/") || rel.contains("/node_modules/")
        || rel.startsWith(".git/") || rel.contains("/.git/");
  }

  private static String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
