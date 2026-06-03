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

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.transform.ExistXQueryTransformer;

import ro.sync.document.DocumentPositionedInfo;
import ro.sync.exml.editor.xmleditor.ErrorListException;
import ro.sync.exml.plugin.transform.XQueryTransformerPluginExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

/**
 * Registers an "eXist-db (HTTP)" XQuery engine in Oxygen's XQuery validation/transformation engine
 * list. Selecting it (for any XQuery document, stored in eXist or on disk) validates with eXist's
 * actual compiler via {@code /api/langservice/diagnostics} — so eXist-specific functions
 * ({@code util:}, {@code xmldb:}, …) and module imports resolved from the database are understood,
 * unlike Saxon.
 *
 * <p>Validation happens here, when the transformer is created: a compile-check that throws
 * {@link ErrorListException} carrying the problems (which Oxygen renders as squiggles + Problems
 * view). Execution lives in {@link ExistXQueryTransformer} (not yet wired).</p>
 */
public final class ExistdbXQueryTransformerPluginExtension implements XQueryTransformerPluginExtension {

  private static final String ENGINE_NAME = "eXist-db (HTTP)";
  private static final String SCHEME = "exist:";

  @Override
  public String getTransformerName() {
    return ENGINE_NAME;
  }

  @Override
  public String getDisplayTransformerName() {
    return ENGINE_NAME;
  }

  @Override
  public boolean suportsAutomaticValidation() {
    return true;
  }

  @Override
  public Transformer getXQueryTransformer(Source xquerySource, URIResolver uriResolver,
      boolean createForValidation) throws ErrorListException {
    ExistClient client = ExistContext.client();
    String query = readQuery(xquerySource);
    // Degrade gracefully: no connection or unreadable source -> no eXist diagnostics.
    if (client != null && !query.isEmpty()) {
      List<DocumentPositionedInfo> problems = compileCheck(client, query, xquerySource.getSystemId());
      if (!problems.isEmpty()) {
        throw new ErrorListException(problems);
      }
    }
    return new ExistXQueryTransformer();
  }

  private List<DocumentPositionedInfo> compileCheck(ExistClient client, String query,
      String systemId) {
    try {
      List<ExistClient.Diagnostic> diagnostics = client.diagnostics(query, moduleLoadPath(systemId));
      List<DocumentPositionedInfo> problems = new ArrayList<>(diagnostics.size());
      for (ExistClient.Diagnostic d : diagnostics) {
        // existdb-openapi reports 0-based line/column; Oxygen positions are 1-based.
        problems.add(new DocumentPositionedInfo(
            severity(d.severity()), cleanMessage(d.message()), systemId,
            d.line() + 1, d.column() + 1));
      }
      return problems;
    } catch (IOException e) {
      return new ArrayList<>();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ArrayList<>();
    }
  }

  /**
   * The module-import base. For resources opened from eXist, the parent collection as an
   * {@code xmldb:exist://} URI — the scheme is what lets eXist resolve relative, bare-{@code /db},
   * and {@code xmldb:} import hints alike. On-disk files get no DB base (empty).
   */
  private static String moduleLoadPath(String systemId) {
    if (systemId == null || !systemId.startsWith(SCHEME)) {
      return "";
    }
    String path = systemId.substring(SCHEME.length());
    int slash = path.lastIndexOf('/');
    String collection = slash > 0 ? path.substring(0, slash) : "/db";
    return "xmldb:exist://" + collection;
  }

  /** The message already carries the {@code err:CODE}; drop eXist's exception-class prefix. */
  private static String cleanMessage(String message) {
    if (message == null) {
      return "";
    }
    String prefix = "org.exist.xquery.XPathException: ";
    String trimmed = message.strip();
    return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
  }

  private static int severity(int langserviceSeverity) {
    return switch (langserviceSeverity) {
      case 1 -> DocumentPositionedInfo.SEVERITY_ERROR;
      case 2 -> DocumentPositionedInfo.SEVERITY_WARN;
      default -> DocumentPositionedInfo.SEVERITY_INFO;
    };
  }

  private static String readQuery(Source source) {
    if (!(source instanceof StreamSource streamSource)) {
      return "";
    }
    Reader reader = streamSource.getReader();
    if (reader == null && streamSource.getInputStream() != null) {
      reader = new InputStreamReader(streamSource.getInputStream(), StandardCharsets.UTF_8);
    }
    if (reader == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    char[] buffer = new char[4096];
    try (BufferedReader buffered = new BufferedReader(reader)) {
      int n;
      while ((n = buffered.read(buffer)) >= 0) {
        sb.append(buffer, 0, n);
      }
    } catch (IOException e) {
      return "";
    }
    return sb.toString();
  }
}
