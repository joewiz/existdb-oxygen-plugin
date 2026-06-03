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

import com.existdb.oxygen.client.ExistClient;

import java.util.List;

import org.junit.jupiter.api.Test;

import ro.sync.document.DocumentPositionedInfo;

/** Unit tests for the diagnostic → {@link DocumentPositionedInfo} mapping. */
class DiagnosticMapperTest {

  @Test
  void severityMapsErrorWarnAndDefaultsToInfo() {
    assertEquals(DocumentPositionedInfo.SEVERITY_ERROR, DiagnosticMapper.severity(1));
    assertEquals(DocumentPositionedInfo.SEVERITY_WARN, DiagnosticMapper.severity(2));
    assertEquals(DocumentPositionedInfo.SEVERITY_INFO, DiagnosticMapper.severity(3));
    assertEquals(DocumentPositionedInfo.SEVERITY_INFO, DiagnosticMapper.severity(99));
  }

  @Test
  void toProblemsShiftsCoordinatesToOneBasedAndCleansMessage() {
    ExistClient.Diagnostic d = new ExistClient.Diagnostic(
        4, 7, 1, "XPST0008",
        "org.exist.xquery.XPathException: err:XPST0008 variable $x is not defined");
    List<DocumentPositionedInfo> problems = DiagnosticMapper.toProblems(List.of(d), "exist:/db/a.xq");

    assertEquals(1, problems.size());
    DocumentPositionedInfo p = problems.get(0);
    assertEquals(5, p.getLine());
    assertEquals(8, p.getColumn());
    assertEquals(DocumentPositionedInfo.SEVERITY_ERROR, p.getSeverity());
    assertEquals("exist:/db/a.xq", p.getSystemID());
    assertEquals("err:XPST0008 variable $x is not defined", p.getMessage());
  }

  @Test
  void toProblemsEmptyForNoDiagnostics() {
    assertEquals(0, DiagnosticMapper.toProblems(List.of(), "exist:/db/a.xq").size());
  }
}
