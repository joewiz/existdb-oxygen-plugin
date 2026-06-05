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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure language-service helpers. */
class LangServiceSupportTest {

  // ---- serverId / dbPath ----

  @Test
  void serverIdReadsTheAuthority() {
    assertEquals("srv-1", LangServiceSupport.serverId("exist://srv-1/db/apps/foo/bar.xq"));
    assertEquals("", LangServiceSupport.serverId("exist:/db/legacy.xq"));
    assertEquals("", LangServiceSupport.serverId("file:/tmp/x.xq"));
    assertEquals("", LangServiceSupport.serverId(null));
  }

  @Test
  void dbPathStripsSchemeAuthorityAndQuery() {
    assertEquals("/db/apps/foo/bar.xq",
        LangServiceSupport.dbPath("exist://srv-1/db/apps/foo/bar.xq"));
    assertEquals("/db/apps/foo/bar.xq",
        LangServiceSupport.dbPath("exist://srv-1/db/apps/foo/bar.xq?v=2"));
    assertEquals("/db/legacy.xq", LangServiceSupport.dbPath("exist:/db/legacy.xq"));
    assertEquals("", LangServiceSupport.dbPath("file:/tmp/x.xq"));
  }

  // ---- moduleLoadPath(String) ----

  @Test
  void moduleLoadPathTakesParentCollectionAsXmldbUri() {
    assertEquals("xmldb:exist:///db/apps/foo",
        LangServiceSupport.moduleLoadPath("exist://srv-1/db/apps/foo/bar.xq"));
  }

  @Test
  void moduleLoadPathForResourceDirectlyInDb() {
    assertEquals("xmldb:exist:///db",
        LangServiceSupport.moduleLoadPath("exist://srv-1/db/bar.xq"));
  }

  @Test
  void moduleLoadPathIgnoresQuerySuffix() {
    assertEquals("xmldb:exist:///db/apps/foo",
        LangServiceSupport.moduleLoadPath("exist://srv-1/db/apps/foo/bar.xq?v=2"));
  }

  @Test
  void moduleLoadPathEmptyForNonExistSystemId() {
    assertEquals("", LangServiceSupport.moduleLoadPath("file:/tmp/local.xq"));
  }

  @Test
  void moduleLoadPathEmptyForNullSystemId() {
    assertEquals("", LangServiceSupport.moduleLoadPath((String) null));
  }

  // ---- moduleLoadPath(URL) ----

  @Test
  void moduleLoadPathFromExistUrl() throws Exception {
    URL url = ExistURLStreamHandler.toUrl("srv-1", "/db/apps/foo/bar.xq");
    assertEquals("xmldb:exist:///db/apps/foo", LangServiceSupport.moduleLoadPath(url));
  }

  @Test
  void moduleLoadPathEmptyForNullUrl() {
    assertEquals("", LangServiceSupport.moduleLoadPath((URL) null));
  }

  @Test
  void moduleLoadPathEmptyForNonExistUrl() throws Exception {
    assertEquals("", LangServiceSupport.moduleLoadPath(new URL("http://localhost/db/x.xq")));
  }

  // ---- trailingIdentifier ----

  @Test
  void trailingIdentifierCapturesNamespacePrefix() {
    assertEquals("util:", LangServiceSupport.trailingIdentifier("let $x := util:"));
  }

  @Test
  void trailingIdentifierCapturesPartialLocalName() {
    assertEquals("util:lo", LangServiceSupport.trailingIdentifier("util:lo"));
  }

  @Test
  void trailingIdentifierEmptyAfterNonIdentifierChar() {
    assertEquals("", LangServiceSupport.trailingIdentifier("1 + "));
  }

  // ---- filterAndSort ----

  @Test
  void filterAndSortMatchesLocalNameByFilterText() {
    List<ExistClient.Completion> all = List.of(
        completion("util:log"), completion("util:eval"), completion("fn:count"));
    // Typed "util:lo" → local "lo" matches util:log's filterText "log" only.
    List<ExistClient.Completion> filtered = LangServiceSupport.filterAndSort(all, "util:lo");
    assertEquals(1, filtered.size());
    assertEquals("util:log", filtered.get(0).label());
  }

  @Test
  void filterAndSortBarePrefixMatchesLocalName() {
    List<ExistClient.Completion> all = List.of(completion("util:log"), completion("fn:count"));
    // Bare "cou" matches fn:count's filterText "count".
    List<ExistClient.Completion> filtered = LangServiceSupport.filterAndSort(all, "cou");
    assertEquals(1, filtered.size());
    assertEquals("fn:count", filtered.get(0).label());
  }

  @Test
  void filterAndSortIsCaseInsensitive() {
    List<ExistClient.Completion> all = List.of(completion("util:log"), completion("fn:count"));
    assertEquals(1, LangServiceSupport.filterAndSort(all, "COU").size());
  }

  @Test
  void filterAndSortReturnsAllSortedWhenLocalEmpty() {
    // A namespace-only token ("util:") has an empty local part; the server already scoped it.
    List<ExistClient.Completion> all = List.of(completion("fn:count"), completion("util:eval"));
    List<ExistClient.Completion> result = LangServiceSupport.filterAndSort(all, "util:");
    assertEquals(2, result.size());
    assertEquals("fn:count", result.get(0).label()); // sorted by sortText (= label)
  }

  @Test
  void filterAndSortFallsBackToAllWhenNothingMatches() {
    List<ExistClient.Completion> all = List.of(completion("util:log"), completion("fn:count"));
    assertEquals(2, LangServiceSupport.filterAndSort(all, "zzz").size());
  }

  // ---- cleanMessage ----

  @Test
  void cleanMessageStripsXPathExceptionPrefix() {
    assertEquals("err:XPST0008 variable $x is not defined",
        LangServiceSupport.cleanMessage(
            "org.exist.xquery.XPathException: err:XPST0008 variable $x is not defined"));
  }

  @Test
  void cleanMessageLeavesUnprefixedMessageUntouched() {
    assertEquals("err:XPST0003 syntax error",
        LangServiceSupport.cleanMessage("  err:XPST0003 syntax error  "));
  }

  @Test
  void cleanMessageNullBecomesEmpty() {
    assertEquals("", LangServiceSupport.cleanMessage(null));
  }

  // ---- isIdentifierChar ----

  @Test
  void isIdentifierCharCoversXQueryNameChars() {
    assertTrue(LangServiceSupport.isIdentifierChar('a'));
    assertTrue(LangServiceSupport.isIdentifierChar('9'));
    assertTrue(LangServiceSupport.isIdentifierChar(':'));
    assertTrue(LangServiceSupport.isIdentifierChar('-'));
    assertTrue(LangServiceSupport.isIdentifierChar('_'));
    assertTrue(LangServiceSupport.isIdentifierChar('$'));
    assertFalse(LangServiceSupport.isIdentifierChar(' '));
    assertFalse(LangServiceSupport.isIdentifierChar('('));
  }

  private static ExistClient.Completion completion(String label) {
    String local = label.contains(":") ? label.substring(label.lastIndexOf(':') + 1) : label;
    return new ExistClient.Completion(label, 3, null, null, label, local, label, 1);
  }
}
