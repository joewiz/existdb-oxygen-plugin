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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure language-service helpers. */
class LangServiceSupportTest {

  // ---- moduleLoadPath(String) ----

  @Test
  void moduleLoadPathTakesParentCollectionAsXmldbUri() {
    assertEquals("xmldb:exist:///db/apps/foo",
        LangServiceSupport.moduleLoadPath("exist:/db/apps/foo/bar.xq"));
  }

  @Test
  void moduleLoadPathForResourceDirectlyInDb() {
    assertEquals("xmldb:exist:///db",
        LangServiceSupport.moduleLoadPath("exist:/db/bar.xq"));
  }

  @Test
  void moduleLoadPathIgnoresQuerySuffix() {
    assertEquals("xmldb:exist:///db/apps/foo",
        LangServiceSupport.moduleLoadPath("exist:/db/apps/foo/bar.xq?v=2"));
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
    URL url = ExistURLStreamHandler.toUrl("/db/apps/foo/bar.xq");
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

  // ---- filterByPrefix ----

  @Test
  void filterByPrefixKeepsMatchingLabels() {
    List<ExistClient.Completion> all = List.of(
        completion("util:log"), completion("util:eval"), completion("fn:count"));
    List<ExistClient.Completion> filtered = LangServiceSupport.filterByPrefix(all, "util:");
    assertEquals(2, filtered.size());
    assertTrue(filtered.stream().allMatch(c -> c.label().startsWith("util:")));
  }

  @Test
  void filterByPrefixIsCaseInsensitive() {
    List<ExistClient.Completion> all = List.of(completion("util:log"), completion("fn:count"));
    assertEquals(1, LangServiceSupport.filterByPrefix(all, "UTIL:").size());
  }

  @Test
  void filterByPrefixReturnsAllWhenPrefixEmpty() {
    List<ExistClient.Completion> all = List.of(completion("util:log"), completion("fn:count"));
    assertSame(all, LangServiceSupport.filterByPrefix(all, ""));
  }

  @Test
  void filterByPrefixFallsBackToAllWhenNothingMatches() {
    List<ExistClient.Completion> all = List.of(completion("util:log"), completion("fn:count"));
    assertSame(all, LangServiceSupport.filterByPrefix(all, "zzz"));
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
    return new ExistClient.Completion(label, 3, null, null, label);
  }
}
