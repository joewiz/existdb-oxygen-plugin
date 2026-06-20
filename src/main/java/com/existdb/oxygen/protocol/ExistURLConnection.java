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
import com.existdb.oxygen.client.ExistHttpException;
import com.existdb.oxygen.client.MimeTypes;
import com.existdb.oxygen.lang.LangServiceSupport;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.model.SerializationOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
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

  /** The DB path, e.g. {@code /db/apps/x.xq} from {@code exist://srv-1/db/apps/x.xq}. */
  private String dbPath() {
    return LangServiceSupport.dbPath(getURL().toExternalForm());
  }

  @Override
  public void connect() throws IOException {
    if (connected) {
      return;
    }
    ExistClient client = requireClient();
    try {
      // Read bytes correctly for the resource type: textual resources (XQuery, XML, JSON, …) come
      // from the JSON envelope's UTF-8 content — eXist flags XQuery as binary, yet its content is
      // real source, and the raw streaming endpoint would *execute* an .xq/.xqm rather than return
      // it — while genuinely-binary resources (images, PDFs, fonts) come back as raw bytes.
      // Apply the user's "Open" serialization defaults (esp. expand-xincludes=no so editing and
      // re-saving an <xi:include> document doesn't silently expand and destroy the includes).
      ExistClient.ResourceBytes rb = client.readResource(dbPath(), openSerialization());
      this.content = rb.bytes();
      this.mimeType = rb.mimeType();
    } catch (ExistHttpException e) {
      // Honor the URLConnection contract: a missing resource is FileNotFoundException, not a
      // generic error. This lets Oxygen show its "Missing File — keep open?" prompt and still
      // re-save the editor (the save PUT re-creates the resource).
      if (e.getStatusCode() == 404) {
        throw (FileNotFoundException) new FileNotFoundException(dbPath()).initCause(e);
      }
      throw e;
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
    // When re-creating a deleted resource the GET never ran, so mimeType is null; without a
    // mime-type eXist tries to store the content as XML (an .xq then fails to parse). Guess from
    // the extension so XQuery/text resources are stored correctly.
    final String mime = mimeType != null ? mimeType : MimeTypes.byName(path);
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

  /** The "Open" serialization defaults, or {@code null} before the plugin has published the store. */
  private static SerializationOptions openSerialization() {
    ProfileStore store = ExistContext.profileStore();
    return store == null ? null : store.openSerialization();
  }

  private ExistClient requireClient() throws IOException {
    // Route to the server named in the exist://<id>/… URL (falls back to the default server).
    ExistClient client = ExistContext.clientFor(getURL());
    if (client == null) {
      throw new IOException("No active eXist-db connection. Connect via the eXist-db view first.");
    }
    return client;
  }
}
