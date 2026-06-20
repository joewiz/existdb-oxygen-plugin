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
package com.existdb.oxygen.model;

/**
 * The serialization parameters the plugin asks existdb-openapi to apply when it serializes an XML
 * resource for reading — used per operation (opening into the editor vs. downloading/exporting to
 * disk). Maps to existdb-openapi query parameters: the W3C {@code indent} and
 * {@code omit-xml-declaration}, plus the eXist serializer extension {@code expand-xincludes} (set
 * {@code no} to preserve {@code <xi:include>} elements across a read-edit-save round-trip).
 *
 * <p>eXist-specific serializer extensions take an {@code exist.} prefix on the query string
 * ({@code exist.expand-xincludes}); the standard W3C parameters do not. An unprefixed
 * {@code expand-xincludes} is silently ignored by the server (and the includes get expanded), so the
 * prefix is required for the preserve behavior.</p>
 */
public record SerializationOptions(boolean indent, boolean expandXIncludes,
    boolean omitXmlDeclaration) {

  /**
   * The leading-{@code &} query-string fragment for these options, with {@code yes}/{@code no}
   * values, e.g. {@code &indent=yes&omit-xml-declaration=yes&exist.expand-xincludes=no}. The eXist
   * extension is {@code exist.}-prefixed; the W3C parameters are not.
   */
  public String toQueryFragment() {
    return "&indent=" + yesNo(indent)
        + "&omit-xml-declaration=" + yesNo(omitXmlDeclaration)
        + "&exist.expand-xincludes=" + yesNo(expandXIncludes);
  }

  private static String yesNo(boolean value) {
    return value ? "yes" : "no";
  }
}
