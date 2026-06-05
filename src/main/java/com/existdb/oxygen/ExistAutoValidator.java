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
import com.existdb.oxygen.lang.DiagnosticMapper;
import com.existdb.oxygen.lang.LangServiceSupport;

import ro.sync.document.DocumentPositionedInfo;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.results.ResultsManager;
import ro.sync.exml.workspace.api.results.ResultsManager.ResultType;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

/**
 * Validates {@code exist:} XQuery editors automatically — no manual engine selection. When such an
 * editor opens, its content is compile-checked via {@code /api/langservice/diagnostics} (on open and
 * after each edit, debounced) and the problems are pushed to Oxygen's Problems view through
 * {@link ResultsManager}. Complements the "eXist-db (HTTP)" validation engine, which a user can
 * still select for native inline squiggles; this gives the eXist-accurate problem list without that
 * step. Diagnostics needs no existdb-openapi langservice fix, so this works against a stock server.
 */
public final class ExistAutoValidator {

  /** Results-view tab the problems are grouped under. */
  private static final String RESULTS_TAB = "eXist-db";
  /** Only XQuery resources are compile-checked; other types (.xml, .md, …) are left alone. */
  private static final Set<String> XQUERY_EXTENSIONS =
      Set.of(".xq", ".xqm", ".xquery", ".xqy", ".xql", ".xqws");
  /** Quiet period after the last keystroke before re-validating, in milliseconds. */
  private static final int DEBOUNCE_MS = 600;
  private static final int AREA = StandalonePluginWorkspace.MAIN_EDITING_AREA;

  private final transient StandalonePluginWorkspace workspace;
  /** Problems by editor system id (URL external form), flattened into the Problems view. */
  private final Map<String, List<DocumentPositionedInfo>> problemsByFile = new HashMap<>();
  /** Per-editor hooks, kept so they can be detached when the editor closes. */
  private final Map<URL, EditorHooks> hooks = new HashMap<>();
  /** Whether our Results tab currently shows problems — so we don't surface an empty pane. */
  private boolean hasShownProblems;

  public ExistAutoValidator(StandalonePluginWorkspace workspace) {
    this.workspace = workspace;
  }

  /** Starts listening for editor open/close so {@code exist:} XQuery editors are auto-validated. */
  public void install() {
    workspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorOpened(URL editorLocation) {
        onOpened(editorLocation);
      }

      @Override
      public void editorClosed(URL editorLocation) {
        onClosed(editorLocation);
      }

      @Override
      public void editorRelocated(URL previousLocation, URL newLocation) {
        onClosed(previousLocation);
        onOpened(newLocation);
      }
    }, AREA);
  }

  /**
   * True for resources this validator handles: XQuery files opened from eXist over the
   * {@code exist:} scheme. Non-XQuery resources (.xml, .md, .html, …) are skipped so they aren't
   * compile-checked as XQuery.
   */
  static boolean isAutoValidated(URL editorLocation) {
    return editorLocation != null && "exist".equals(editorLocation.getProtocol())
        && isXQuery(editorLocation);
  }

  /** True if the location's extension is an XQuery one, regardless of protocol (exist:, file:, …). */
  static boolean isXQuery(URL editorLocation) {
    if (editorLocation == null) {
      return false;
    }
    String form = editorLocation.toExternalForm();
    int query = form.indexOf('?');
    String path = (query >= 0 ? form.substring(0, query) : form).toLowerCase(Locale.ROOT);
    return XQUERY_EXTENSIONS.stream().anyMatch(path::endsWith);
  }

  private void onOpened(URL location) {
    if (!isAutoValidated(location) || hooks.containsKey(location)) {
      return;
    }
    JTextComponent component = textComponent(location);
    if (component == null) {
      return;
    }
    Timer debounce = new Timer(DEBOUNCE_MS, e -> validate(location));
    debounce.setRepeats(false);
    DocumentListener listener = new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        debounce.restart();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        debounce.restart();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        // Attribute changes only; no revalidation needed.
      }
    };
    Document document = component.getDocument();
    document.addDocumentListener(listener);
    hooks.put(location, new EditorHooks(document, listener, debounce));
    validate(location);
  }

  private void onClosed(URL location) {
    EditorHooks removed = hooks.remove(location);
    if (removed != null) {
      removed.detach();
    }
    if (location != null && problemsByFile.remove(location.toExternalForm()) != null) {
      pushResults();
    }
  }

  /** Runs diagnostics for the editor off the EDT and refreshes the Problems view on completion. */
  private void validate(URL location) {
    ExistClient client = ExistContext.clientFor(location);
    JTextComponent component = textComponent(location);
    if (client == null || component == null) {
      // Not connected (or editor gone): clear any stale problems for this file.
      if (location != null && problemsByFile.remove(location.toExternalForm()) != null) {
        pushResults();
      }
      return;
    }
    final String systemId = location.toExternalForm();
    final String text = component.getText();
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(location);

    new SwingWorker<List<DocumentPositionedInfo>, Void>() {
      @Override
      protected List<DocumentPositionedInfo> doInBackground() throws Exception {
        return DiagnosticMapper.toProblems(client.diagnostics(text, moduleLoadPath), systemId);
      }

      @Override
      protected void done() {
        try {
          problemsByFile.put(systemId, get());
          pushResults();
        } catch (Exception e) {
          // Validation failed (network, interrupt, …); leave prior problems in place.
          Thread.currentThread().interrupt();
        }
      }
    }.execute();
  }

  /** Replaces the eXist Problems-view contents with the union of every tracked editor's problems. */
  private void pushResults() {
    List<DocumentPositionedInfo> all = new ArrayList<>();
    for (List<DocumentPositionedInfo> fileProblems : problemsByFile.values()) {
      all.addAll(fileProblems);
    }
    // Don't surface (pop up) the Results view when a clean file is opened/validated: only push when
    // there are problems, or to clear ones we previously showed.
    if (all.isEmpty() && !hasShownProblems) {
      return;
    }
    workspace.getResultsManager().setResults(RESULTS_TAB, all, ResultType.PROBLEM);
    hasShownProblems = !all.isEmpty();
  }

  private JTextComponent textComponent(URL location) {
    if (location == null) {
      return null;
    }
    WSEditor editor = workspace.getEditorAccess(location, AREA);
    if (editor == null) {
      return null;
    }
    WSEditorPage page = editor.getCurrentPage();
    if (page instanceof WSTextEditorPage textPage
        && textPage.getTextComponent() instanceof JTextComponent component) {
      return component;
    }
    return null;
  }

  /** A document listener + debounce timer bound to one editor, detachable on close. */
  private record EditorHooks(Document document, DocumentListener listener, Timer debounce) {
    void detach() {
      debounce.stop();
      document.removeDocumentListener(listener);
    }
  }
}
