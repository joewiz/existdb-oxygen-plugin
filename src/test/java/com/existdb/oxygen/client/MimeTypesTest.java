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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MimeTypes} extension mapping. */
class MimeTypesTest {

  @Test
  void mapsXQueryAndXmlAndText() {
    assertEquals("application/xquery", MimeTypes.byName("module.xqm"));
    assertEquals("application/xquery", MimeTypes.byName("/db/apps/foo/index.xq"));
    assertEquals("application/xml", MimeTypes.byName("data.xml"));
    assertEquals("text/markdown", MimeTypes.byName("README.md"));
  }

  @Test
  void caseInsensitiveOnExtension() {
    assertEquals("application/xquery", MimeTypes.byName("Module.XQ"));
  }

  @Test
  void nullForUnknownOrMissingExtension() {
    assertNull(MimeTypes.byName("photo.heic"));
    assertNull(MimeTypes.byName("Makefile"));
    assertNull(MimeTypes.byName("/db/dir.with.dots/noext"));
    assertNull(MimeTypes.byName(null));
  }

  @Test
  void textualForXQueryAndTextTypesEvenThoughEXistStoresXQueryAsBinary() {
    // The crux of the .xq/.xqm open fix: eXist flags XQuery as binary, but it's textual source —
    // so it must be read from the JSON envelope, not the (module-executing) streaming endpoint.
    assertTrue(MimeTypes.isTextual("application/xquery"));
    assertTrue(MimeTypes.isTextual("text/plain"));
    assertTrue(MimeTypes.isTextual("application/xml"));
    assertTrue(MimeTypes.isTextual("application/json"));
    assertTrue(MimeTypes.isTextual("application/javascript"));
    assertTrue(MimeTypes.isTextual("image/svg+xml"));
    assertTrue(MimeTypes.isTextual("APPLICATION/XQUERY"));
  }

  @Test
  void notTextualForGenuineBinaryTypes() {
    assertFalse(MimeTypes.isTextual("image/png"));
    assertFalse(MimeTypes.isTextual("application/pdf"));
    assertFalse(MimeTypes.isTextual("application/octet-stream"));
    assertFalse(MimeTypes.isTextual("font/woff2"));
    assertFalse(MimeTypes.isTextual(null));
  }
}
