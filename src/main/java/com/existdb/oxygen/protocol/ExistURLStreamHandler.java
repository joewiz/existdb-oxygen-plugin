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

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/** Stream handler for the {@code exist:} scheme; hands back {@link ExistURLConnection}s. */
public final class ExistURLStreamHandler extends URLStreamHandler {

  /** The scheme this handler serves. */
  public static final String PROTOCOL = "exist";

  @Override
  protected URLConnection openConnection(URL url) throws IOException {
    return new ExistURLConnection(url);
  }

  /** Builds an {@code exist:} URL for a DB path, e.g. {@code /db/apps/myapp/index.xq}. */
  public static URL toUrl(String dbPath) throws IOException {
    String path = dbPath.startsWith("/") ? dbPath : "/" + dbPath;
    return new URL(null, PROTOCOL + ":" + path, new ExistURLStreamHandler());
  }
}
