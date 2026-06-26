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

import com.existdb.oxygen.model.ConnectionProfile;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;

/**
 * Raised when a request can't reach the eXist-db server (as opposed to {@link ExistHttpException},
 * which the server returned). Its message names the server and suggests a fix, so the raw
 * {@code java.net.ConnectException} / {@code null} that the underlying exceptions carry never
 * surfaces to the user.
 */
public class ExistConnectionException extends IOException {

  private ExistConnectionException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Wraps a recognized connection failure in a user-facing message naming the server, or returns
   * {@code null} if the cause isn't a connection problem (the caller then rethrows it unchanged).
   */
  static ExistConnectionException from(ConnectionProfile profile, IOException cause) {
    String server = "\"" + profile.getName() + "\" at " + profile.getBaseUrl();
    String message;
    if (cause instanceof UnknownHostException) {
      message = "Can't resolve the host for eXist-db server " + server
          + ". Check the host name in the connection settings.";
    } else if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
      message = "Can't reach eXist-db server " + server
          + ". Check that the server is running and the connection settings are correct.";
    } else if (cause instanceof HttpTimeoutException) {
      message = "eXist-db server " + server
          + " didn't respond in time. It may be overloaded or unreachable.";
    } else if (cause instanceof SSLException) {
      message = "Couldn't establish a secure (HTTPS) connection to eXist-db server " + server
          + ". For a self-signed certificate, enable \"Accept self-signed certificate\" in the"
          + " connection settings.";
    } else {
      return null;
    }
    return new ExistConnectionException(message, cause);
  }
}
