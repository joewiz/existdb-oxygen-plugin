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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Resolves which eXist-db server a filesystem project deploys to, recognizing the two connection
 * conventions in the eXist toolchain: the {@code .existdb.json} sync descriptor (eXistdb VS Code
 * plugin / langserver) and a {@code .env} file ({@code EXISTDB_SERVER}, the xst / node-exist
 * convention).
 *
 * <p>Discovery is a single <em>closest-ancestor</em> walk: the first directory (from the selection
 * up to the project root) that has either file wins; if a directory has <em>both</em>,
 * {@code .existdb.json} takes precedence — it is the richer, plugin-native descriptor (it also
 * carries the sync target and build section), whereas {@code .env} is connection-only. Only the
 * server URL is read; credentials always come from the matched saved connection, never the
 * dotfile's (possibly plaintext) password.</p>
 */
public final class ProjectConnection {

  /** A resolved server: its eXist root URL and which file it came from (for display). */
  public record Resolved(String serverRoot, String source) {
  }

  private ProjectConnection() {
  }

  /**
   * The server for {@code start}, by the closest-ancestor walk described above, bounded at
   * {@code stopAt} (inclusive; {@code null} for the filesystem root). Empty if neither file is found.
   */
  public static Optional<Resolved> resolve(File start, File stopAt) {
    File dir = start == null ? null : (start.isDirectory() ? start : start.getParentFile());
    while (dir != null) {
      String fromDescriptor = serverFromDescriptor(new File(dir, ExistdbProjectConfig.FILE_NAME));
      if (fromDescriptor != null) {
        return Optional.of(new Resolved(fromDescriptor, ExistdbProjectConfig.FILE_NAME));
      }
      String fromEnv = serverFromEnv(new File(dir, ".env"));
      if (fromEnv != null) {
        return Optional.of(new Resolved(fromEnv, ".env"));
      }
      if (stopAt != null && dir.equals(stopAt)) {
        break;
      }
      dir = dir.getParentFile();
    }
    return Optional.empty();
  }

  private static String serverFromDescriptor(File descriptor) {
    if (!descriptor.isFile()) {
      return null;
    }
    try {
      return ExistdbProjectConfig.parse(descriptor).serverUrl();
    } catch (IOException | RuntimeException e) {
      return null; // malformed — let the caller fall through to a .env in the same directory
    }
  }

  /** The {@code EXISTDB_SERVER} value from a {@code .env} file, or {@code null}. */
  static String serverFromEnv(File envFile) {
    if (!envFile.isFile()) {
      return null;
    }
    try {
      for (String raw : Files.readAllLines(envFile.toPath())) {
        String line = raw.strip();
        if (line.startsWith("export ")) {
          line = line.substring("export ".length()).strip();
        }
        if (line.startsWith("#") || !line.startsWith("EXISTDB_SERVER")) {
          continue;
        }
        int eq = line.indexOf('=');
        if (eq < 0 || !"EXISTDB_SERVER".equals(line.substring(0, eq).strip())) {
          continue;
        }
        String value = unquote(line.substring(eq + 1).strip());
        if (!value.isEmpty()) {
          return value;
        }
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
