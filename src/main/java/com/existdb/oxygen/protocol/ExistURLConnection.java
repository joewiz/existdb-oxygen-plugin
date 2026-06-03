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
package com.existdb.oxygen.protocol;

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.client.ExistClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * A {@link URLConnection} for {@code exist:} URLs. Reads pull the resource from existdb-openapi
 * ({@code GET /api/db/resource}); writes push it back ({@code PUT /api/db/resource}) when the
 * output stream is closed — which is exactly what Oxygen's native Save does.
 *
 * <p>The URL path component is the DB path, e.g. {@code exist:/db/apps/myapp/index.xq}.</p>
 */
final class ExistURLConnection extends URLConnection {

  private byte[] content;
  private String mimeType;

  ExistURLConnection(URL url) {
    super(url);
  }

  private String dbPath() {
    // For "exist:/db/apps/x.xq" the path is "/db/apps/x.xq".
    String path = getURL().getPath();
    if (path == null || path.isEmpty()) {
      // Fall back to the scheme-specific part for opaque forms ("exist:/db/...").
      String ssp = getURL().toExternalForm().substring("exist:".length());
      int q = ssp.indexOf('?');
      path = q >= 0 ? ssp.substring(0, q) : ssp;
    }
    return path;
  }

  @Override
  public void connect() throws IOException {
    if (connected) {
      return;
    }
    ExistClient client = requireClient();
    try {
      ExistClient.ResourceContent rc = client.getResource(dbPath());
      this.mimeType = rc.mimeType();
      this.content = rc.content().getBytes(StandardCharsets.UTF_8);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while reading " + dbPath(), e);
    }
    connected = true;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    connect();
    return new ByteArrayInputStream(content);
  }

  @Override
  public String getContentType() {
    return mimeType != null ? mimeType : guessContentTypeFromName(dbPath());
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    final ExistClient client = requireClient();
    final String path = dbPath();
    final String mime = mimeType;
    // Buffer the editor's bytes; flush to the DB with a PUT when Oxygen closes the stream.
    return new ByteArrayOutputStream() {
      private boolean closed;

      @Override
      public void close() throws IOException {
        if (closed) {
          return;
        }
        closed = true;
        String text = new String(toByteArray(), StandardCharsets.UTF_8);
        try {
          client.putResource(path, text, mime);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while saving " + path, e);
        }
      }
    };
  }

  @Override
  public boolean getDoOutput() {
    return true;
  }

  private ExistClient requireClient() throws IOException {
    ExistClient client = ExistContext.client();
    if (client == null) {
      throw new IOException("No active eXist-db connection. Connect via the eXist-db view first.");
    }
    return client;
  }
}
