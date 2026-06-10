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
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.Comparator;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;

/**
 * A non-modal sitewide full-text search ({@code /api/search}): pick a server, type a query, and
 * browse hits with snippets; double-click (or Open) opens the source document over {@code exist:}.
 */
public final class SearchDialog extends JDialog {

  private static final int LIMIT = 50;
  /** The scope the field picker discovers searchable fields under (the whole database). */
  private static final String FIELD_SCOPE = "/db";
  private static final String INTRO_TEXT = "Searches across data that apps on the selected eXist-db "
      + "server contribute to a Lucene full-text field called \"site-content\"; eXist 7's stock apps "
      + "contribute their data to this.";

  private final transient StandalonePluginWorkspace workspace;
  private final transient List<ConnectionProfile> profiles;
  private final JComboBox<String> serverCombo;
  private final JTextField queryField = OxygenUIComponentsFactory.createTextField();
  /** "Search in" picker: a {@code null} item is "All fields"; others are discovered fields/facets. */
  private final JComboBox<ExistClient.SearchFieldInfo> fieldCombo =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>());
  private final JLabel fieldHint = new JLabel(" ");
  private final DefaultListModel<ExistClient.SearchHit> model = new DefaultListModel<>();
  private final JList<ExistClient.SearchHit> list = new JList<>(model);
  private final JLabel status = new JLabel(" ");
  /** Intro line; its HTML wrap-width is re-bound to the dialog width on resize (see updateIntro). */
  private final JLabel intro = new JLabel();

  private SearchDialog(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    super(owner, "Search eXist-db", false);
    this.workspace = workspace;
    this.profiles = store.loadAll();
    String[] names = profiles.stream().map(ConnectionProfile::getName).toArray(String[]::new);
    serverCombo = OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(names));
    selectDefaultServer(store.defaultProfileId());
    buildUi();
    setSize(640, 420);
    setLocationRelativeTo(owner);
  }

  /** Opens (or focuses) a non-modal search window. */
  public static void open(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    SearchDialog dialog = new SearchDialog(owner, store, workspace);
    dialog.setVisible(true);
    dialog.queryField.requestFocusInWindow();
  }

  private void selectDefaultServer(String defaultId) {
    for (int i = 0; i < profiles.size(); i++) {
      if (profiles.get(i).getId() != null && profiles.get(i).getId().equals(defaultId)) {
        serverCombo.setSelectedIndex(i);
        return;
      }
    }
  }

  private void buildUi() {
    JButton searchButton = OxygenUIComponentsFactory.createButton(new AbstractAction("Search") {
      @Override
      public void actionPerformed(ActionEvent e) {
        doSearch();
      }
    });
    JPanel top = new JPanel(new BorderLayout(6, 0));
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    left.add(new JLabel("Server:"));
    left.add(serverCombo);
    top.add(left, BorderLayout.WEST);
    top.add(queryField, BorderLayout.CENTER);
    top.add(searchButton, BorderLayout.EAST);
    queryField.addActionListener(e -> doSearch()); // Enter runs the search

    // "Search in" picker, populated from /api/search/fields for the selected server (FLS-filtered).
    fieldCombo.setRenderer(fieldRenderer());
    fieldCombo.setToolTipText("The searchable fields/facets this server exposes (only those you may "
        + "see). Per-field search needs eXist #6455; for now the query runs across the default field.");
    fieldHint.setForeground(Color.GRAY);
    JPanel fieldRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    fieldRow.add(new JLabel("Search in:"));
    fieldRow.add(fieldCombo);
    fieldRow.add(fieldHint);
    serverCombo.addActionListener(e -> fetchFields()); // re-discover when the server changes

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(hitRenderer());
    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          openSelected();
        }
      }
    });
    // Cells wrap to the list width; on resize, invalidate the cached cell sizes so they re-measure
    // (and re-wrap) at the new width instead of overflowing horizontally.
    list.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        list.setFixedCellHeight(10);
        list.setFixedCellHeight(-1);
      }
    });

    // Match Oxygen's Open/Find Resource dialog: Cancel then Open (rightmost); Open is the blue
    // default, enabled only when a result is selected.
    JButton open = OxygenUIComponentsFactory.createButton(new AbstractAction("Open") {
      @Override
      public void actionPerformed(ActionEvent e) {
        openSelected();
      }
    });
    open.setEnabled(false);
    list.addListSelectionListener(e -> open.setEnabled(list.getSelectedValue() != null));
    JButton cancel = OxygenUIComponentsFactory.createButton(new AbstractAction("Cancel") {
      @Override
      public void actionPerformed(ActionEvent e) {
        dispose();
      }
    });
    JPanel south = new JPanel(new BorderLayout());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(cancel);
    buttons.add(open);
    // Status goes in CENTER, not WEST: a WEST label takes its full preferred width, so a long error
    // string (e.g. a 500's raw JSON body) overflows and overlaps the buttons. CENTER gets only the
    // space left after the buttons and clips the text instead of colliding with them.
    south.add(status, BorderLayout.CENTER);
    south.add(buttons, BorderLayout.EAST);

    // Intro line (like Oxygen's "Install new add-ons" dialog) explaining what /api/search covers.
    // An HTML label whose wrap-width tracks the dialog width: a wrapping JTextArea collapses to one
    // line in BorderLayout.NORTH (it computes its height before it knows its width), and a fixed-px
    // HTML width clips when the window is narrower. Re-bind the width on resize instead.
    intro.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 2));
    updateIntro();
    addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        updateIntro();
      }
    });
    JPanel controls = new JPanel(new BorderLayout(0, 2));
    controls.add(top, BorderLayout.NORTH);
    controls.add(fieldRow, BorderLayout.CENTER);
    JPanel north = new JPanel(new BorderLayout(0, 4));
    north.add(intro, BorderLayout.NORTH);
    north.add(controls, BorderLayout.CENTER);
    fetchFields(); // discover fields for the initially-selected server

    setLayout(new BorderLayout(8, 8));
    ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    add(north, BorderLayout.NORTH);
    add(OxygenUIComponentsFactory.createScrollPane(list,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(open);
    getRootPane().registerKeyboardAction(e -> dispose(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JPanel.WHEN_IN_FOCUSED_WINDOW);
  }

  /**
   * Re-renders the intro at a wrap width matching the current dialog width. Uses a table cell with a
   * {@code width} attribute — Swing's {@code JLabel} HTML honors that for wrapping, but ignores
   * {@code width} on {@code <body>}/{@code <div>} (which lays out as one long, clipped line).
   */
  private void updateIntro() {
    int width = getContentPane().getWidth() - 32;
    if (width < 120) {
      width = 560;
    }
    intro.setText("<html><table><tr><td width='" + width + "'>" + escape(INTRO_TEXT)
        + "</td></tr></table></html>");
  }

  private String selectedServerId() {
    int i = serverCombo.getSelectedIndex();
    return i >= 0 && i < profiles.size() ? profiles.get(i).getId() : null;
  }

  private void doSearch() {
    String serverId = selectedServerId();
    ExistClient client = ExistContext.clientById(serverId);
    if (client == null) {
      workspace.showInformationMessage("Select a connected server first.");
      return;
    }
    String query = queryField.getText().trim();
    if (query.isEmpty()) {
      return;
    }
    status.setText("Searching…");
    status.setToolTipText(null);
    model.clear();
    new SwingWorker<ExistClient.SearchResults, Void>() {
      @Override
      protected ExistClient.SearchResults doInBackground() throws Exception {
        return client.search(query, LIMIT);
      }

      @Override
      protected void done() {
        try {
          ExistClient.SearchResults results = get();
          results.hits().forEach(model::addElement);
          int shown = results.hits().size();
          status.setText(results.total() > shown
              ? "Showing " + shown + " of " + results.total() + " matches"
              : shown + " match" + (shown == 1 ? "" : "es"));
          if (!model.isEmpty()) {
            // Move focus to the results and select the first hit, so Up/Down navigate immediately
            // and Enter (the default Open button) opens the selection.
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
            list.requestFocusInWindow();
          }
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          String message = "Search failed: " + cause.getMessage();
          status.setText(message);
          status.setToolTipText(message); // CENTER clips a long error; keep the full text on hover.
        }
      }
    }.execute();
  }

  /** Discovers the searchable fields for the selected server and repopulates the "Search in" combo. */
  private void fetchFields() {
    fieldCombo.removeAllItems();
    fieldCombo.addItem(null); // "All fields"
    fieldHint.setText(" ");
    ExistClient client = ExistContext.clientById(selectedServerId());
    if (client == null) {
      return;
    }
    new SwingWorker<ExistClient.SearchFields, Void>() {
      @Override
      protected ExistClient.SearchFields doInBackground() throws Exception {
        return client.searchFields(FIELD_SCOPE);
      }

      @Override
      protected void done() {
        try {
          ExistClient.SearchFields fields = get();
          fields.fields().stream()
              .sorted(Comparator
                  .comparingInt((ExistClient.SearchFieldInfo f) -> kindOrder(f.kind()))
                  .thenComparing(ExistClient.SearchFieldInfo::field, String.CASE_INSENSITIVE_ORDER))
              .forEach(fieldCombo::addItem);
          fieldHint.setText(fields.total() + " visible to " + fields.user());
        } catch (Exception e) {
          // An older server (no /api/search/fields) just leaves "All fields"; nothing to surface.
          fieldHint.setText(" ");
        }
      }
    }.execute();
  }

  /** Sort order for the picker: plain fields first, then facets, then vectors. */
  private static int kindOrder(String kind) {
    return switch (kind == null ? "" : kind) {
      case "field" -> 0;
      case "facet" -> 1;
      case "vector" -> 2;
      default -> 3;
    };
  }

  /** Renders a "Search in" item: {@code null} = "All fields"; others show the name + grey kind, with
   *  the element/type/analyzer contract as a tooltip. */
  private static DefaultListCellRenderer fieldRenderer() {
    return new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> jlist, Object value, int index,
          boolean selected, boolean focus) {
        ExistClient.SearchFieldInfo f = (ExistClient.SearchFieldInfo) value;
        if (f == null) {
          Component c =
              super.getListCellRendererComponent(jlist, "All fields (default)", index, selected, focus);
          setToolTipText(null);
          return c;
        }
        Color secondary = selected ? jlist.getSelectionForeground() : Color.GRAY;
        String hex = String.format("#%02x%02x%02x",
            secondary.getRed(), secondary.getGreen(), secondary.getBlue());
        String text = "<html>" + escape(f.field()) + " <font color='" + hex + "'>— "
            + escape(f.kind()) + "</font></html>";
        Component c = super.getListCellRendererComponent(jlist, text, index, selected, focus);
        setToolTipText(fieldTooltip(f));
        return c;
      }
    };
  }

  private static String fieldTooltip(ExistClient.SearchFieldInfo f) {
    StringBuilder sb = new StringBuilder("<html><b>").append(escape(f.field())).append("</b> (")
        .append(escape(f.kind())).append(")");
    if (f.type() != null) {
      sb.append("<br>type: ").append(escape(f.type()));
    }
    if (!f.elements().isEmpty()) {
      sb.append("<br>elements: ").append(escape(String.join(", ", f.elements())));
    }
    if (!f.analyzers().isEmpty()) {
      sb.append("<br>analyzer: ").append(escape(String.join(", ", f.analyzers())));
    }
    return sb.append("</html>").toString();
  }

  private void openSelected() {
    ExistClient.SearchHit hit = list.getSelectedValue();
    if (hit == null || hit.path().isEmpty()) {
      return;
    }
    try {
      URL url = ExistURLStreamHandler.toUrl(selectedServerId(), hit.path());
      if (!workspace.open(url)) {
        workspace.showErrorMessage("Oxygen declined to open " + hit.path());
      }
    } catch (Exception e) {
      workspace.showErrorMessage("Failed to open " + hit.path() + ": " + e.getMessage());
    }
  }

  private static DefaultListCellRenderer hitRenderer() {
    return new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> list, Object value, int index,
          boolean selected, boolean focus) {
        ExistClient.SearchHit hit = (ExistClient.SearchHit) value;
        String title = hit.title() == null || hit.title().isBlank() || "(untitled)".equals(hit.title())
            ? leaf(hit.path()) : hit.title();
        // The path/snippet are de-emphasized in gray, but gray-on-blue is unreadable when the row is
        // selected — use the list's selection foreground there so the secondary text stays legible.
        Color secondary = selected ? list.getSelectionForeground() : Color.GRAY;
        String hex = String.format("#%02x%02x%02x",
            secondary.getRed(), secondary.getGreen(), secondary.getBlue());
        // Wrap to the list's width (minus insets/scrollbar) instead of overflowing horizontally.
        // A table cell's width attribute is the constraint JLabel HTML actually honors (width on
        // body/div is ignored — the line stays full length and clips).
        int width = list.getWidth();
        int wrapWidth = width > 60 ? width - 28 : 560;
        String html = "<html><table><tr><td width='" + wrapWidth + "'><b>" + escape(title)
            + "</b> &nbsp; <font color='" + hex + "'>" + escape(hit.path()) + "</font><br>"
            + "<font color='" + hex + "'>" + highlightSnippet(hit.snippet())
            + "</font></td></tr></table></html>";
        Component c = super.getListCellRendererComponent(list, html, index, selected, focus);
        setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        return c;
      }
    };
  }

  private static String leaf(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static String snippet(String snippet) {
    String s = snippet == null ? "" : snippet.replaceAll("\\s+", " ").trim();
    return s.length() > 200 ? s.substring(0, 200) + "…" : s;
  }

  /**
   * Renders a snippet as HTML: collapse/truncate, escape, then turn the server's KWIC {@code <mark>}
   * hit markers into a highlighted span (black on yellow, so it stays readable on selected rows too).
   * Snippets without {@code <mark>} simply render as plain text.
   */
  private static String highlightSnippet(String raw) {
    return escape(snippet(raw))
        .replace("&lt;mark&gt;", "<span style='background-color:#fff59d; color:#000000'>")
        .replace("&lt;/mark&gt;", "</span>");
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(640, 420);
  }
}
