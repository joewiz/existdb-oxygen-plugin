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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Shared filesystem → eXist-db upload helpers, used by both the eXist-db pane's drag-and-drop import
 * and the Project-pane "Upload to eXist…" action. Binary files (detected by a NUL byte in the head)
 * are stored via the raw streaming PUT; text files via a tolerant text PUT that retries as
 * {@code text/plain} when the server rejects content as non-XML.
 */
public final class Uploads {

  private Uploads() {
  }

  /**
   * Uploads {@code file} — or, recursively, a directory and its contents — under {@code parentPath},
   * returning the number of resources stored. A directory becomes a child collection named after it;
   * missing ancestor collections of {@code parentPath} itself are <em>not</em> created here (callers
   * that need that should {@link ExistClient#createCollection} first). When {@code includeHidden} is
   * false, dot-prefixed files and directories are skipped (so {@code .git}, {@code .DS_Store}, etc.
   * aren't uploaded).
   */
  public static int uploadRecursive(ExistClient client, String parentPath, File file,
      boolean includeHidden) throws IOException, InterruptedException {
    if (!includeHidden && isHidden(file)) {
      return 0;
    }
    if (file.isDirectory()) {
      String collection = parentPath + "/" + file.getName();
      client.createCollection(collection);
      int count = 0;
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          count += uploadRecursive(client, collection, child, includeHidden);
        }
      }
      return count;
    }
    byte[] bytes = Files.readAllBytes(file.toPath());
    String path = parentPath + "/" + file.getName();
    String mime = MimeTypes.byName(file.getName());
    if (isBinary(bytes)) {
      client.putResourceBytes(path, bytes, mime); // null mime → application/octet-stream
    } else {
      // A known extension picks the right mime; otherwise store as plain text (never XML-parsed).
      putResourceTolerant(client, path, new String(bytes, StandardCharsets.UTF_8),
          mime != null ? mime : "text/plain");
    }
    return 1;
  }

  /** A file is "hidden" if its name starts with a dot (the cross-platform convention we honor). */
  public static boolean isHidden(File file) {
    return file.getName().startsWith(".");
  }

  /** Heuristic: a NUL byte in the head means binary (existdb-openapi can't store binary as text). */
  public static boolean isBinary(byte[] bytes) {
    int limit = Math.min(bytes.length, 8192);
    for (int i = 0; i < limit; i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Stores text content, retrying as {@code text/plain} if the server rejects it as malformed XML
   * (so an {@code .xml}-named file that isn't well-formed still uploads as plain text).
   */
  public static void putResourceTolerant(ExistClient client, String path, String content,
      String mime) throws IOException, InterruptedException {
    try {
      client.putResource(path, content, mime);
    } catch (ExistHttpException e) {
      if ("text/plain".equals(mime) || !isXmlParseError(e)) {
        throw e;
      }
      client.putResource(path, content, "text/plain");
    }
  }

  private static boolean isXmlParseError(ExistHttpException e) {
    String body = e.getResponseBody();
    return body != null
        && (body.contains("XML parser") || body.contains("Content is not allowed in prolog"));
  }
}
