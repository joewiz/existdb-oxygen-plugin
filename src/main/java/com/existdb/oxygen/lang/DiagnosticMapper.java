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

import ro.sync.document.DocumentPositionedInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps existdb-openapi langservice diagnostics to Oxygen {@link DocumentPositionedInfo} problems.
 * Shared by the validation engine (which throws them as an {@code ErrorListException}) and the
 * auto-validator (which pushes them to the Problems view), so the coordinate and severity mapping
 * lives in one place.
 */
public final class DiagnosticMapper {

  private DiagnosticMapper() {
  }

  /**
   * Maps each diagnostic to a {@link DocumentPositionedInfo} for {@code systemId}. existdb-openapi
   * reports 0-based line/column; Oxygen positions are 1-based, so each is shifted by one.
   */
  public static List<DocumentPositionedInfo> toProblems(
      List<ExistClient.Diagnostic> diagnostics, String systemId) {
    List<DocumentPositionedInfo> problems = new ArrayList<>(diagnostics.size());
    for (ExistClient.Diagnostic d : diagnostics) {
      problems.add(new DocumentPositionedInfo(
          severity(d.severity()), LangServiceSupport.cleanMessage(d.message()), systemId,
          d.line() + 1, d.column() + 1));
    }
    return problems;
  }

  /** langservice severity (1=error, 2=warning, else info/hint) → {@code DocumentPositionedInfo}. */
  public static int severity(int langserviceSeverity) {
    return switch (langserviceSeverity) {
      case 1 -> DocumentPositionedInfo.SEVERITY_ERROR;
      case 2 -> DocumentPositionedInfo.SEVERITY_WARN;
      default -> DocumentPositionedInfo.SEVERITY_INFO;
    };
  }
}
