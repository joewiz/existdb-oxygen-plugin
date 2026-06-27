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
package com.existdb.oxygen.ui;

import com.existdb.oxygen.ExistContext;
import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.client.XQueryError;
import com.existdb.oxygen.lang.LangServiceSupport;
import com.existdb.oxygen.query.QueryRunner;

import ro.sync.document.DocumentPositionedInfo;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.results.ResultsManager;
import ro.sync.exml.workspace.api.results.ResultsManager.ResultType;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.event.ActionEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.SwingWorker;
import javax.swing.text.JTextComponent;

/**
 * "Run Current Editor": executes the active editor's XQuery (or its selection, if any) against the
 * connected eXist server via {@code /api/query} and opens the serialized results in a new editor —
 * no transformation scenario to configure. Available from the eXist-db menu, the editor pop-up, and
 * a toolbar button.
 */
public final class RunCurrentEditorAction extends AbstractAction {

  private static final String RESULTS_TAB = "eXist-db XQuery Results";
  private static final int MAX_ROW_LENGTH = 200;

  private final transient StandalonePluginWorkspace workspace;
  private final transient ResultSourceNavigator navigator;

  public RunCurrentEditorAction(StandalonePluginWorkspace workspace) {
    super("Run Current Editor (eXist)");
    this.workspace = workspace;
    this.navigator = new ResultSourceNavigator(workspace);
    // Activating a stored-node result row jumps to the originating element in its source document;
    // other rows fall through to Oxygen's default navigation (the serialized-output editor).
    workspace.getResultsManager().addEventHandler(RESULTS_TAB, navigator);
    putValue(SHORT_DESCRIPTION, "Run the current editor's XQuery against eXist");
    URL icon = RunCurrentEditorAction.class.getResource("/images/run-query.png");
    if (icon != null) {
      putValue(SMALL_ICON, new ImageIcon(icon));
    }
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      workspace.showInformationMessage("Open an XQuery editor to run.");
      return;
    }
    // Route to the editor's own server; the default server for an unsaved/local query.
    final ExistClient client = ExistContext.clientFor(editor.getEditorLocation());
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      return;
    }
    final String serverId = ExistContext.serverIdFor(editor.getEditorLocation());
    final JTextComponent component = (JTextComponent) page.getTextComponent();
    // Run the selection if there is one, else the whole editor; remember where the selection starts
    // so an error's selection-relative line/column maps back to the document for caret-jump.
    final String selection = component.getSelectedText();
    final boolean usingSelection = selection != null && !selection.isBlank();
    final String query = usingSelection ? selection : component.getText();
    final int queryBaseOffset = usingSelection ? component.getSelectionStart() : 0;
    if (query.isBlank()) {
      workspace.showInformationMessage("Nothing to run — the editor is empty.");
      return;
    }
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(editor.getEditorLocation());
    workspace.showStatusMessage("Running XQuery against eXist…");

    new SwingWorker<QueryRunner.QueryResult, Void>() {
      @Override
      protected QueryRunner.QueryResult doInBackground() throws Exception {
        return QueryRunner.execute(client, query, moduleLoadPath);
      }

      @Override
      protected void done() {
        try {
          openResults(get(), serverId);
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          // Move the caret to the error position (when present) before the dialog, so OK leaves the
          // caret on the offending spot.
          QueryErrorCaret.jumpTo(component, query, queryBaseOffset, cause);
          workspace.showErrorMessage(XQueryError.describe("XQuery failed", cause));
        }
      }
    }.execute();
  }

  private void openResults(QueryRunner.QueryResult result, String serverId) {
    if (result.totalItems() == 0) {
      workspace.showStatusMessage("eXist-db: the query returned no results.");
      return;
    }
    boolean xml = QueryRunner.looksLikeXml(result.output());
    boolean wrapped = xml && result.items().size() > 1;
    String content = result.output();
    if (wrapped) {
      // A sequence of more than one node is not a well-formed XML document on its own (XML allows
      // only a single root element). Wrap it in a neutral, no-namespace container so Oxygen opens
      // it as valid XML — and so the first child's document-type association (e.g. DocBook → Author
      // mode) doesn't fire on a fragment and report a spurious parse error over valid results.
      content = "<results>\n" + content + "\n</results>";
    }
    URL resultsUrl =
        workspace.createNewEditor(xml ? "xml" : "txt", xml ? "text/xml" : "text/plain", content);
    if (resultsUrl != null) {
      depositResults(resultsUrl, result.items(), wrapped, serverId);
    }
    if (result.truncated()) {
      workspace.showStatusMessage("eXist-db: showing the first " + QueryRunner.MAX_ITEMS
          + " of " + result.totalItems() + " items.");
    } else {
      workspace.showStatusMessage("eXist-db: " + result.totalItems() + " item(s).");
    }
  }

  /**
   * Lists one navigable row per result in the <b>Results</b> view, each pointing at the line in the
   * results editor where that item begins. This works for every result type — atomic values like
   * {@code 1 to 10} as well as nodes — because navigation targets the serialized output rather than a
   * source document. (Once existdb-openapi populates each result's source URI/position, node rows can
   * point back to the originating element instead.)
   */
  private void depositResults(
      URL resultsUrl, List<QueryRunner.Item> items, boolean wrapped, String serverId) {
    String systemId = resultsUrl.toString();
    List<DocumentPositionedInfo> rows = new ArrayList<>(items.size());
    // Stored-node rows that should jump to their source document instead of the results editor.
    Map<DocumentPositionedInfo, ResultSourceNavigator.NodeRef> sourceRows = new HashMap<>();
    // 1-based line where the first item begins; the <results> wrapper, if added, occupies line 1.
    int line = wrapped ? 2 : 1;
    for (QueryRunner.Item item : items) {
      // line/column = where the item begins (column 1, since items start a line); length = the
      // item's full serialized character count, so double-clicking selects the whole element/value
      // rather than just its first line.
      DocumentPositionedInfo row = new DocumentPositionedInfo(DocumentPositionedInfo.SEVERITY_INFO,
          rowMessage(item), systemId, line, 1, item.value().length());
      rows.add(row);
      if (item.hasSource()) {
        sourceRows.put(row,
            new ResultSourceNavigator.NodeRef(serverId, item.documentURI(), item.nodeId()));
      }
      line += lineAdvance(item.value());
    }
    navigator.setRows(sourceRows);
    ResultsManager resultsManager = workspace.getResultsManager();
    resultsManager.setResults(RESULTS_TAB, rows, ResultType.GENERIC);
  }

  /** A one-line, type-prefixed snippet of an item's value for the Results-view row. */
  private static String rowMessage(QueryRunner.Item item) {
    String firstLine = item.value().strip();
    int nl = firstLine.indexOf('\n');
    if (nl >= 0) {
      firstLine = firstLine.substring(0, nl).strip() + " …";
    }
    if (firstLine.length() > MAX_ROW_LENGTH) {
      firstLine = firstLine.substring(0, MAX_ROW_LENGTH) + " …";
    }
    return item.type().isEmpty() ? firstLine : item.type() + ": " + firstLine;
  }

  /**
   * How many lines to advance from one item's start line to the next item's: the number of newlines
   * within the value (lines it spans, minus one) plus one for the {@code '\n'} join separator. So a
   * single-line value advances 1; a value spanning three lines advances 3.
   */
  private static int lineAdvance(String value) {
    int newlines = 0;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == '\n') {
        newlines++;
      }
    }
    return newlines + 1;
  }

  private static WSTextEditorPage textPage(WSEditor editor) {
    if (editor == null) {
      return null;
    }
    WSEditorPage current = editor.getCurrentPage();
    return current instanceof WSTextEditorPage textPage ? textPage : null;
  }
}
