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
package com.existdb.oxygen.lang;

import com.existdb.oxygen.client.ExistClient;

import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure, Oxygen-free helpers shared by the language-service entry points (the validation engine and
 * the go-to-definition / completion / hover actions). Kept here so the string logic stays unit
 * testable without an Oxygen workspace.
 */
public final class LangServiceSupport {

  private static final String SCHEME = "exist:";
  private static final String SCHEME_AUTHORITY = "exist://";
  private static final String CLASS_PREFIX = "org.exist.xquery.XPathException: ";

  private LangServiceSupport() {
  }

  /**
   * The server id in an {@code exist://<id>/…} system id, or {@code ""} when there is no authority
   * (a legacy {@code exist:/db/…} form or a non-{@code exist:} id).
   */
  public static String serverId(String systemId) {
    if (systemId == null || !systemId.startsWith(SCHEME_AUTHORITY)) {
      return "";
    }
    int slash = systemId.indexOf('/', SCHEME_AUTHORITY.length());
    return slash < 0
        ? systemId.substring(SCHEME_AUTHORITY.length())
        : systemId.substring(SCHEME_AUTHORITY.length(), slash);
  }

  /**
   * The DB path ({@code /db/…}) in an {@code exist:} system id — with an {@code //<id>} authority or
   * without — or {@code ""} for a {@code null}/non-{@code exist:} id. An optional {@code ?query}
   * suffix is dropped.
   */
  public static String dbPath(String systemId) {
    if (systemId == null || !systemId.startsWith(SCHEME)) {
      return "";
    }
    String rest = systemId.substring(SCHEME.length());
    if (rest.startsWith("//")) {
      int slash = rest.indexOf('/', 2);
      rest = slash < 0 ? "" : rest.substring(slash);
    }
    int query = rest.indexOf('?');
    return query >= 0 ? rest.substring(0, query) : rest;
  }

  /**
   * The module-import base for an {@code exist:} resource: its parent collection as an
   * {@code xmldb:exist://} URI. The scheme is what lets eXist resolve relative, bare-{@code /db},
   * and {@code xmldb:} import hints alike. Returns {@code ""} for a {@code null} or non-{@code exist:}
   * system id (e.g. an on-disk file), which signals "no DB base".
   *
   * @param systemId an {@code exist:}-scheme system id or external URL form, e.g.
   *     {@code exist://srv-1/db/apps/foo/bar.xq} (an optional {@code ?query} suffix is ignored)
   */
  public static String moduleLoadPath(String systemId) {
    String path = dbPath(systemId);
    if (path.isEmpty()) {
      return "";
    }
    int slash = path.lastIndexOf('/');
    String collection = slash > 0 ? path.substring(0, slash) : "/db";
    return "xmldb:exist://" + collection;
  }

  /** As {@link #moduleLoadPath(String)}, from an editor location URL ({@code null}/non-exist → ""). */
  public static String moduleLoadPath(URL location) {
    if (location == null || !"exist".equals(location.getProtocol())) {
      return "";
    }
    return moduleLoadPath(location.toExternalForm());
  }

  /**
   * The identifier being typed immediately before the end of {@code expression} (e.g. {@code util:}
   * or {@code util:log}), used to scope completion proposals. Empty when the expression doesn't end
   * in an identifier character.
   */
  public static String trailingIdentifier(String expression) {
    int start = expression.length();
    while (start > 0 && isIdentifierChar(expression.charAt(start - 1))) {
      start--;
    }
    return expression.substring(start);
  }

  /**
   * Filters proposals by {@code filterText} against the local-name part of the typed token (so bare
   * {@code cou} matches {@code fn:count}) and orders them by {@code sortText}. The server scopes by
   * namespace (existdb-openapi #42); this refines the bare-prefix case and sorts. Falls back to the
   * sorted full list when the prefix is empty or nothing matches.
   */
  public static List<ExistClient.Completion> filterAndSort(
      List<ExistClient.Completion> proposals, String typedToken) {
    String local = localName(typedToken).toLowerCase(Locale.ROOT);
    List<ExistClient.Completion> sorted = proposals.stream()
        .sorted(Comparator.comparing(LangServiceSupport::sortKey))
        .toList();
    if (local.isEmpty()) {
      return sorted;
    }
    List<ExistClient.Completion> matches = sorted.stream()
        .filter(c -> matchText(c).toLowerCase(Locale.ROOT).startsWith(local))
        .toList();
    return matches.isEmpty() ? sorted : matches;
  }

  /** The local name of a token: the part after the last {@code :} (a namespace prefix), if any. */
  public static String localName(String token) {
    if (token == null) {
      return "";
    }
    int colon = token.lastIndexOf(':');
    return colon >= 0 ? token.substring(colon + 1) : token;
  }

  /** Whether a proposal's local name starts with {@code lowerPrefix} (used for live type-to-filter). */
  public static boolean matchesLocal(ExistClient.Completion c, String lowerPrefix) {
    return lowerPrefix.isEmpty() || matchText(c).toLowerCase(Locale.ROOT).startsWith(lowerPrefix);
  }

  private static String matchText(ExistClient.Completion c) {
    return c.filterText() != null && !c.filterText().isEmpty() ? c.filterText() : c.label();
  }

  private static String sortKey(ExistClient.Completion c) {
    return c.sortText() != null && !c.sortText().isEmpty() ? c.sortText() : c.label();
  }

  /** The message already carries the {@code err:CODE}; drop eXist's exception-class prefix. */
  public static String cleanMessage(String message) {
    if (message == null) {
      return "";
    }
    String trimmed = message.strip();
    return trimmed.startsWith(CLASS_PREFIX) ? trimmed.substring(CLASS_PREFIX.length()) : trimmed;
  }

  /** Characters that make up an XQuery name token for completion prefixing (incl. {@code :$-_}). */
  public static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_' || c == '$';
  }
}
