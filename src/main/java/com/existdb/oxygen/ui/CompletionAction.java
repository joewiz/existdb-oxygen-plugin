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
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
  // LSP CompletionItemKind: 3 = function, 6 = variable, 14 = keyword.
  private static final ImageIcon FUNCTION_ICON = icon("/images/node-customizer/XSLFunction16.png");
  private static final ImageIcon VARIABLE_ICON = icon("/images/node-customizer/XSLVariable16.png");

  private final transient StandalonePluginWorkspace workspace;

  public CompletionAction(StandalonePluginWorkspace workspace) {
    super("eXist Completion");
    this.workspace = workspace;
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      return;
    }
    final ExistClient client = ExistContext.clientFor(editor.getEditorLocation());
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
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
          // Keep the full server-scoped list and let the type-to-filter field narrow it live; seed
          // the field with the local name already typed before the caret (e.g. "w" of "util:w").
          List<ExistClient.Completion> proposals = LangServiceSupport.filterAndSort(get(), "");
          if (proposals.isEmpty()) {
            workspace.showStatusMessage("eXist: no completions.");
          } else {
            showPopup(component, caret, proposals, LangServiceSupport.localName(prefix));
          }
        } catch (Exception e) {
          workspace.showErrorMessage("eXist completion failed: " + e.getMessage());
        }
      }
    }.execute();
  }

  private void showPopup(JTextComponent component, int caret,
      List<ExistClient.Completion> items, String initialPrefix) {
    DefaultListModel<ExistClient.Completion> model = new DefaultListModel<>();

    JList<ExistClient.Completion> list = new JList<>(model);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(completionRenderer());

    JEditorPane doc = new JEditorPane("text/html", "");
    doc.setEditable(false);
    doc.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    JScrollPane docScroll = new JScrollPane(doc);
    docScroll.setPreferredSize(new Dimension(320,
        Math.min(items.size(), MAX_VISIBLE_ROWS) * ROW_HEIGHT + 4));
    list.addListSelectionListener(e -> showDoc(doc, list.getSelectedValue()));

    // Type-to-filter field: live-narrows the list by the proposals' local names (eXide behavior).
    JTextField filter = new JTextField(initialPrefix);
    Runnable refilter = () -> {
      String prefix = filter.getText().toLowerCase(Locale.ROOT);
      model.clear();
      for (ExistClient.Completion c : items) {
        if (LangServiceSupport.matchesLocal(c, prefix)) {
          model.addElement(c);
        }
      }
      if (!model.isEmpty()) {
        list.setSelectedIndex(0);
      }
    };
    filter.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        refilter.run();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        refilter.run();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        refilter.run();
      }
    });

    JPopupMenu popup = new JPopupMenu();
    popup.setLayout(new BorderLayout());
    JScrollPane listScroll = new JScrollPane(list);
    JPanel listPane = new JPanel(new BorderLayout());
    listPane.add(filter, BorderLayout.NORTH);
    listPane.add(listScroll, BorderLayout.CENTER);
    listPane.setPreferredSize(new Dimension(340,
        Math.min(items.size(), MAX_VISIBLE_ROWS) * ROW_HEIGHT + 4 + filter.getPreferredSize().height));
    popup.add(listPane, BorderLayout.WEST);
    popup.add(docScroll, BorderLayout.CENTER);

    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          accept(list, popup, component, caret);
        }
      }
    });
    // Keystrokes go to the filter field (it keeps focus); steer the list and accept/cancel from it.
    filter.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
          case KeyEvent.VK_ENTER -> accept(list, popup, component, caret);
          case KeyEvent.VK_ESCAPE -> popup.setVisible(false);
          case KeyEvent.VK_DOWN -> moveSelection(list, 1);
          case KeyEvent.VK_UP -> moveSelection(list, -1);
          default -> {
            // Other keys edit the filter text.
          }
        }
      }
    });

    refilter.run();
    try {
      Rectangle2D r = component.modelToView2D(caret);
      popup.show(component, (int) r.getX(), (int) (r.getY() + r.getHeight()));
      filter.requestFocusInWindow();
    } catch (BadLocationException e) {
      // Cannot place the popup; skip.
    }
  }

  /** Moves the list selection by {@code delta} rows, clamped to the model bounds, and scrolls to it. */
  private static void moveSelection(JList<ExistClient.Completion> list, int delta) {
    int size = list.getModel().getSize();
    if (size == 0) {
      return;
    }
    int next = Math.max(0, Math.min(size - 1, list.getSelectedIndex() + delta));
    list.setSelectedIndex(next);
    list.ensureIndexIsVisible(next);
  }

  /** Shows the selected proposal's signature and documentation in the side panel. */
  private static void showDoc(JEditorPane doc, ExistClient.Completion c) {
    if (c == null) {
      doc.setText("");
      return;
    }
    StringBuilder html = new StringBuilder("<html><body style='font-family:sans-serif;font-size:9px'>");
    String signature = c.detail() != null && !c.detail().isEmpty() ? c.detail() : c.label();
    html.append("<b>").append(escape(signature)).append("</b>");
    if (c.documentation() != null && !c.documentation().isEmpty()) {
      html.append("<br><br>").append(escape(c.documentation()));
    }
    html.append("</body></html>");
    doc.setText(html.toString());
    doc.setCaretPosition(0);
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        super.getListCellRendererComponent(list, c.label(), index, selected, focus);
        setIcon(kindIcon(c.kind()));
        return this;
      }
    };
  }

  private static javax.swing.Icon kindIcon(int kind) {
    return switch (kind) {
      case 3 -> FUNCTION_ICON;
      case 6 -> VARIABLE_ICON;
      default -> null;
    };
  }

  private static ImageIcon icon(String resource) {
    java.net.URL url = CompletionAction.class.getResource(resource);
    return url != null ? new ImageIcon(url) : null;
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
