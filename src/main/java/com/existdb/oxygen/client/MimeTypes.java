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

import java.util.Locale;
import java.util.Map;

/**
 * Maps a filename extension to the mime-type eXist should store a resource as — so an {@code .xq}
 * is kept as {@code application/xquery} (binary) rather than being parsed as XML on store. Shared by
 * the {@code exist:} save path and resource uploads.
 *
 * <p>Stopgap: once existdb-openapi infers the type server-side (PR #34) the client need not send a
 * mime-type and this class can be removed.</p>
 */
public final class MimeTypes {

  private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
      Map.entry("xq", "application/xquery"),
      Map.entry("xqm", "application/xquery"),
      Map.entry("xquery", "application/xquery"),
      Map.entry("xqy", "application/xquery"),
      Map.entry("xql", "application/xquery"),
      Map.entry("xqws", "application/xquery"),
      Map.entry("json", "application/json"),
      Map.entry("js", "application/javascript"),
      Map.entry("css", "text/css"),
      Map.entry("html", "text/html"),
      Map.entry("htm", "text/html"),
      Map.entry("md", "text/markdown"),
      Map.entry("txt", "text/plain"),
      Map.entry("xml", "application/xml"),
      Map.entry("xsl", "application/xml"),
      Map.entry("xslt", "application/xml"),
      Map.entry("xsd", "application/xml"),
      Map.entry("rng", "application/xml"),
      Map.entry("svg", "application/xml"));

  private MimeTypes() {
  }

  /** The mime-type for a file name/path's extension, or {@code null} for an unknown extension. */
  public static String byName(String name) {
    if (name == null) {
      return null;
    }
    int dot = name.lastIndexOf('.');
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (dot < 0 || dot < slash) {
      return null;
    }
    return BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }
}
