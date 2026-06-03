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
import com.existdb.oxygen.lang.LangServiceSupport;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

/**
 * "eXist Completion": shows eXist-aware XQuery proposals from {@code /api/langservice/completions}
 * at the caret — eXist's real function library ({@code util:}, {@code xmldb:}, imported DB modules),
 * which Oxygen's built-in (Saxon-based) completion doesn't know. A dedicated action rather than an
 * {@code ExternalContentCompletionProvider}, because Oxygen owns content completion for XQuery and
 * only consults external providers when it has no proposals of its own.
 */
public final class CompletionAction extends AbstractAction {

  private static final int MAX_VISIBLE_ROWS = 10;
  private static final int ROW_HEIGHT = 18;

  private final transient StandalonePluginWorkspace workspace;

  public CompletionAction(StandalonePluginWorkspace workspace) {
    super("eXist Completion");
    this.workspace = workspace;
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    final ExistClient client = ExistContext.client();
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      return;
    }
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      return;
    }
    final JTextComponent component = (JTextComponent) page.getTextComponent();
    final int caret = component.getCaretPosition();
    final String expression = component.getText().substring(0, caret);
    final String prefix = LangServiceSupport.trailingIdentifier(expression);
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(editor.getEditorLocation());

    new SwingWorker<List<ExistClient.Completion>, Void>() {
      @Override
      protected List<ExistClient.Completion> doInBackground() throws Exception {
        return client.completions(expression, moduleLoadPath);
      }

      @Override
      protected void done() {
        try {
          List<ExistClient.Completion> proposals = LangServiceSupport.filterByPrefix(get(), prefix);
          if (proposals.isEmpty()) {
            workspace.showStatusMessage("eXist: no completions.");
          } else {
            showPopup(component, caret, proposals);
          }
        } catch (Exception e) {
          workspace.showErrorMessage("eXist completion failed: " + e.getMessage());
        }
      }
    }.execute();
  }

  private void showPopup(JTextComponent component, int caret, List<ExistClient.Completion> items) {
    DefaultListModel<ExistClient.Completion> model = new DefaultListModel<>();
    items.forEach(model::addElement);

    JList<ExistClient.Completion> list = new JList<>(model);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setSelectedIndex(0);
    list.setCellRenderer(completionRenderer());

    JPopupMenu popup = new JPopupMenu();
    popup.setLayout(new BorderLayout());
    JScrollPane scroll = new JScrollPane(list);
    scroll.setPreferredSize(new Dimension(420, Math.min(items.size(), MAX_VISIBLE_ROWS) * ROW_HEIGHT + 4));
    popup.add(scroll, BorderLayout.CENTER);

    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          accept(list, popup, component, caret);
        }
      }
    });
    list.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          accept(list, popup, component, caret);
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          popup.setVisible(false);
        }
      }
    });

    try {
      Rectangle2D r = component.modelToView2D(caret);
      popup.show(component, (int) r.getX(), (int) (r.getY() + r.getHeight()));
      list.requestFocusInWindow();
    } catch (BadLocationException e) {
      // Cannot place the popup; skip.
    }
  }

  private void accept(JList<ExistClient.Completion> list, JPopupMenu popup,
      JTextComponent component, int caret) {
    ExistClient.Completion selected = list.getSelectedValue();
    if (selected != null) {
      insert(component, caret, selected.insertText());
    }
    popup.setVisible(false);
  }

  private static DefaultListCellRenderer completionRenderer() {
    return new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> list, Object value,
          int index, boolean selected, boolean focus) {
        ExistClient.Completion c = (ExistClient.Completion) value;
        String detail = c.detail() != null && !c.detail().isEmpty() ? "  —  " + c.detail() : "";
        return super.getListCellRendererComponent(list, c.label() + detail, index, selected, focus);
      }
    };
  }

  /** Replaces the identifier prefix immediately before the caret with the proposal's insert text. */
  private static void insert(JTextComponent component, int caret, String insertText) {
    try {
      Document doc = component.getDocument();
      int start = caret;
      while (start > 0 && LangServiceSupport.isIdentifierChar(doc.getText(start - 1, 1).charAt(0))) {
        start--;
      }
      doc.remove(start, caret - start);
      doc.insertString(start, insertText, null);
      component.requestFocusInWindow();
    } catch (BadLocationException e) {
      // Insertion failed; leave the document unchanged.
    }
  }

  private static WSTextEditorPage textPage(WSEditor editor) {
    if (editor == null) {
      return null;
    }
    WSEditorPage current = editor.getCurrentPage();
    return current instanceof WSTextEditorPage textPage ? textPage : null;
  }
}
