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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A parsed {@code .existdb.json} descriptor — the configuration convention shared with vscode-existdb
 * / atom-existdb — used to map a filesystem project (or one repo within it) to an eXist-db server and
 * target collection. Only the subset needed for uploading is read: the chosen server's URL/user and
 * the target collection (the {@code sync.root}, falling back to the server's {@code root}), plus the
 * descriptor's own directory so a file's path relative to it can be mirrored under the collection.
 *
 * <p>Discovery is by <em>closest ancestor-or-self</em> walk ({@link #findNearest}), not project-root
 * assumption: an Oxygen project pane often holds many independent repos (e.g. {@code repos/*}), each
 * its own deployable unit with its own descriptor.
 */
public final class ExistdbProjectConfig {

  /** The standard descriptor filename, shared across eXist editor integrations. */
  public static final String FILE_NAME = ".existdb.json";

  private final File descriptorDir;
  private final String serverUrl;
  private final String user;
  private final String targetCollection;
  private final List<String> ignore;
  private final boolean uploadOnSave;

  private ExistdbProjectConfig(File descriptorDir, String serverUrl, String user,
      String targetCollection, List<String> ignore, boolean uploadOnSave) {
    this.descriptorDir = descriptorDir;
    this.serverUrl = serverUrl;
    this.user = user;
    this.targetCollection = targetCollection;
    this.ignore = ignore;
    this.uploadOnSave = uploadOnSave;
  }

  /** The directory containing the {@code .existdb.json} (the package/repo root for path mapping). */
  public File descriptorDir() {
    return descriptorDir;
  }

  /** The configured server base URL (e.g. {@code http://localhost:8080/exist}), or {@code null}. */
  public String serverUrl() {
    return serverUrl;
  }

  /** The configured user, or {@code null}. */
  public String user() {
    return user;
  }

  /** The target collection ({@code sync.root} or the server's {@code root}), or {@code null}. */
  public String targetCollection() {
    return targetCollection;
  }

  /** The {@code sync.ignore} globs (never {@code null}; empty when unset). */
  public List<String> ignore() {
    return ignore;
  }

  /**
   * Whether {@code sync.onSave} is enabled — opt-in, per-project auto-upload of a saved file to its
   * mapped collection. Off unless the descriptor sets it explicitly.
   */
  public boolean uploadOnSave() {
    return uploadOnSave;
  }

  /**
   * Walks up from {@code start} (a file or directory) toward {@code stopAt} (inclusive; may be
   * {@code null} to walk to the filesystem root) and returns the first usable {@code .existdb.json}
   * found. A malformed descriptor is skipped and the walk continues upward.
   */
  public static Optional<ExistdbProjectConfig> findNearest(File start, File stopAt) {
    if (start == null) {
      return Optional.empty();
    }
    File dir = start.isDirectory() ? start : start.getParentFile();
    File boundary = stopAt == null ? null : stopAt.getAbsoluteFile();
    while (dir != null) {
      File descriptor = new File(dir, FILE_NAME);
      if (descriptor.isFile()) {
        try {
          return Optional.of(parse(descriptor));
        } catch (IOException | RuntimeException e) {
          // Malformed/unreadable descriptor — keep looking further up.
        }
      }
      if (boundary != null && dir.getAbsoluteFile().equals(boundary)) {
        break;
      }
      dir = dir.getParentFile();
    }
    return Optional.empty();
  }

  /** Parses a {@code .existdb.json} file into the upload-relevant configuration. */
  public static ExistdbProjectConfig parse(File descriptor) throws IOException {
    JSONObject root = new JSONObject(Files.readString(descriptor.toPath()));
    JSONObject servers = root.optJSONObject("servers");
    JSONObject sync = root.optJSONObject("sync");
    JSONObject server = resolveServer(servers, sync);

    String serverUrl = server == null ? null : emptyToNull(server.optString("server", null));
    String user = firstNonEmpty(optString(sync, "user"), optString(server, "user"));
    String target = firstNonEmpty(optString(sync, "root"), optString(server, "root"));

    List<String> ignore = new ArrayList<>();
    JSONArray ignoreArray = sync == null ? null : sync.optJSONArray("ignore");
    for (int i = 0; ignoreArray != null && i < ignoreArray.length(); i++) {
      String glob = ignoreArray.optString(i, "");
      if (!glob.isEmpty()) {
        ignore.add(glob);
      }
    }
    boolean onSave = sync != null && sync.optBoolean("onSave", false);
    return new ExistdbProjectConfig(
        descriptor.getParentFile(), serverUrl, user, target, ignore, onSave);
  }

  /**
   * Picks the server definition: the one named by {@code sync.server}, else (mirroring
   * vscode-existdb) the first entry in {@code servers}. Returns {@code null} if none.
   */
  private static JSONObject resolveServer(JSONObject servers, JSONObject sync) {
    if (servers == null || servers.isEmpty()) {
      return null;
    }
    String ref = sync == null ? null : emptyToNull(sync.optString("server", null));
    if (ref != null && servers.has(ref)) {
      return servers.optJSONObject(ref);
    }
    return servers.optJSONObject(servers.keys().next());
  }

  private static String optString(JSONObject obj, String key) {
    return obj == null ? null : emptyToNull(obj.optString(key, null));
  }

  private static String firstNonEmpty(String a, String b) {
    return a != null ? a : b;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
