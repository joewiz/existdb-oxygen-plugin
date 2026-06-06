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
import javax.swing.JTextArea;
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

  private final transient StandalonePluginWorkspace workspace;
  private final transient List<ConnectionProfile> profiles;
  private final JComboBox<String> serverCombo;
  private final JTextField queryField = OxygenUIComponentsFactory.createTextField();
  private final DefaultListModel<ExistClient.SearchHit> model = new DefaultListModel<>();
  private final JList<ExistClient.SearchHit> list = new JList<>(model);
  private final JLabel status = new JLabel(" ");

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

    JButton open = OxygenUIComponentsFactory.createButton(new AbstractAction("Open") {
      @Override
      public void actionPerformed(ActionEvent e) {
        openSelected();
      }
    });
    JButton close = OxygenUIComponentsFactory.createButton(new AbstractAction("Close") {
      @Override
      public void actionPerformed(ActionEvent e) {
        dispose();
      }
    });
    JPanel south = new JPanel(new BorderLayout());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(open);
    buttons.add(close);
    south.add(status, BorderLayout.WEST);
    south.add(buttons, BorderLayout.EAST);

    // Intro line (like Oxygen's "Install new add-ons" dialog) explaining what /api/search covers.
    // A wrapping, non-editable text area follows the viewport width (a fixed-width HTML label clipped).
    JTextArea intro = OxygenUIComponentsFactory.createTextArea(
        "Searches across data that apps on the selected eXist-db server contribute to a Lucene "
            + "full-text field called \"site-content\"; eXist 7's stock apps contribute their data "
            + "to this.");
    intro.setEditable(false);
    intro.setLineWrap(true);
    intro.setWrapStyleWord(true);
    intro.setOpaque(false);
    intro.setFocusable(false);
    intro.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 2));
    JPanel north = new JPanel(new BorderLayout(0, 4));
    north.add(intro, BorderLayout.NORTH);
    north.add(top, BorderLayout.CENTER);

    setLayout(new BorderLayout(8, 8));
    ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    add(north, BorderLayout.NORTH);
    add(OxygenUIComponentsFactory.createScrollPane(list,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(searchButton);
    getRootPane().registerKeyboardAction(e -> dispose(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JPanel.WHEN_IN_FOCUSED_WINDOW);
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
            list.setSelectedIndex(0);
          }
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          status.setText("Search failed: " + cause.getMessage());
        }
      }
    }.execute();
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
        int width = list.getWidth();
        int wrapWidth = width > 60 ? width - 28 : 560;
        String html = "<html><body style='width:" + wrapWidth + "px'><b>" + escape(title)
            + "</b> &nbsp; <font color='" + hex + "'>" + escape(hit.path()) + "</font><br>"
            + "<font color='" + hex + "'>" + highlightSnippet(hit.snippet()) + "</font></body></html>";
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
