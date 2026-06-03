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
package com.existdb.oxygen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import java.net.URL;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@link ExistAutoValidator} resource-eligibility predicate. */
class ExistAutoValidatorTest {

  @Test
  void existResourcesAreAutoValidated() throws Exception {
    URL existResource = ExistURLStreamHandler.toUrl("/db/apps/foo/bar.xq");
    assertTrue(ExistAutoValidator.isAutoValidated(existResource));
  }

  @Test
  void localAndRemoteFilesAreNotAutoValidated() throws Exception {
    assertFalse(ExistAutoValidator.isAutoValidated(new URL("file:/tmp/local.xq")));
    assertFalse(ExistAutoValidator.isAutoValidated(new URL("http://host/db/x.xq")));
  }

  @Test
  void nullLocationIsNotAutoValidated() {
    assertFalse(ExistAutoValidator.isAutoValidated(null));
  }
}
