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

import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.plugin.urlstreamhandler.URLStreamHandlerPluginExtension;

import java.net.URLStreamHandler;

/**
 * Registers the {@code exist:} URL scheme with Oxygen so DB resources opened from the eXist-db
 * view participate in normal open/save. Oxygen routes any {@code exist:} URL through here.
 */
public final class ExistdbURLStreamHandlerPluginExtension implements URLStreamHandlerPluginExtension {

  @Override
  public URLStreamHandler getURLStreamHandler(String protocol) {
    if (ExistURLStreamHandler.PROTOCOL.equals(protocol)) {
      return new ExistURLStreamHandler();
    }
    return null;
  }
}
