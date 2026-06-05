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

import com.existdb.oxygen.client.ExistClient;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * A single window for managing saved eXist servers — a Data Source Explorer-style "Connections"
 * table (Name, URL, Default) with a flat icon toolbar for Add / Edit / Duplicate / Remove and
 * reordering (Move Up/Down, Sort A–Z), plus choosing the default server and testing a connection.
 * Hosted in Oxygen's {@link OKCancelDialog}; edits are made on a working copy and persisted only on
 * <b>OK</b>; <b>Cancel</b> (or Escape) discards them.
 */
public final class ManageServersDialog {

  private final transient Frame owner;
  private final transient ProfileStore store;
  private final transient StandalonePluginWorkspace workspace;
  private final transient List<ConnectionProfile> profiles = new ArrayList<>();
  private final ServerTableModel model = new ServerTableModel();
  // Oxygen's table paints the alternating row stripes and selection used across the workbench.
  private final JTable table = OxygenUIComponentsFactory.createTable(model);

  private transient ConnectionProfile defaultProfile;
  private transient OKCancelDialog host;
  private transient Action moveUpAction;
  private transient Action moveDownAction;

  private ManageServersDialog(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    this.owner = owner;
    this.store = store;
    this.workspace = workspace;
    profiles.addAll(store.loadAll());
    String defaultId = store.defaultProfileId();
    for (ConnectionProfile p : profiles) {
      if (p.getId() != null && p.getId().equals(defaultId)) {
        defaultProfile = p;
      }
    }
  }

  /**
   * Opens the modal dialog. Returns {@code true} if the user clicked OK (changes persisted), so the
   * caller can refresh; {@code false} on Cancel/Escape.
   */
  public static boolean open(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    ManageServersDialog controller = new ManageServersDialog(owner, store, workspace);
    return controller.show();
  }

  private boolean show() {
    host =
        OxygenUIComponentsFactory.createOkCancelDialog(owner, "Configure eXist-db Connections", true);
    host.getContentPane().add(buildContent(), BorderLayout.CENTER);
    if (!profiles.isEmpty()) {
      table.setRowSelectionInterval(0, 0);
    }
    host.setResizable(true);
    host.setSize(720, 380);
    host.setLocationRelativeTo(owner);
    host.setVisible(true);
    if (host.getResult() == OKCancelDialog.RESULT_OK) {
      commit();
      return true;
    }
    return false;
  }

  private JComponent buildContent() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    // ApplicationTable stripes populated rows but not the empty area below the last row, even with
    // fillsViewportHeight and Oxygen's scroll pane; the full-viewport striping the Data Source
    // Explorer shows isn't reachable through the public SDK. Accepted as an SDK gap.
    table.setFillsViewportHeight(true);
    // Name compact, URL gets the room, Default a fixed narrow check column.
    table.getColumnModel().getColumn(0).setPreferredWidth(150);
    table.getColumnModel().getColumn(1).setPreferredWidth(430);
    table.getColumnModel().getColumn(2).setMaxWidth(60);
    table.getColumnModel().getColumn(2).setPreferredWidth(60);
    DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
    centered.setHorizontalAlignment(SwingConstants.CENTER);
    table.getColumnModel().getColumn(2).setCellRenderer(centered);

    // App-specific actions on the left as text buttons.
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    left.add(textButton("Sort A–Z", this::sort));
    left.add(textButton("Set Default", this::setDefault));
    left.add(textButton("Test", this::test));

    // Oxygen Data Sources-style flat icon toolbar on the right.
    JToolBar right = new JToolBar();
    right.setFloatable(false);
    right.setRollover(true);
    right.add(iconButton("/images/Add16.png", "Add…", this::add));
    right.add(iconButton("/images/Wrench16.png", "Edit…", this::edit));
    right.add(iconButton("/images/Copy16.png", "Duplicate", this::duplicate));
    right.add(iconButton("/images/Remove16.png", "Remove…", this::remove));
    right.addSeparator();
    // Yellow move arrows, greyed (disabled) at the first/last position — matching Data Sources.
    moveUpAction = iconAction("/images/UpArrowYellow16.png", "Move up", () -> move(-1));
    moveDownAction = iconAction("/images/DownArrowYellow16.png", "Move down", () -> move(1));
    JButton up = OxygenUIComponentsFactory.createToolbarButton(moveUpAction, false);
    setDisabledIcon(up, "/images/UpGray16.png");
    JButton down = OxygenUIComponentsFactory.createToolbarButton(moveDownAction, false);
    setDisabledIcon(down, "/images/DownGray16.png");
    right.add(up);
    right.add(down);

    JPanel actions = new JPanel(new BorderLayout());
    actions.add(left, BorderLayout.WEST);
    actions.add(right, BorderLayout.EAST);

    JPanel connections = new JPanel(new BorderLayout());
    connections.setBorder(BorderFactory.createTitledBorder("Connections"));
    connections.add(OxygenUIComponentsFactory.createScrollPane(table,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
    connections.add(actions, BorderLayout.SOUTH);

    table.getSelectionModel().addListSelectionListener(e -> updateMoveEnabled());
    updateMoveEnabled();
    return connections;
  }

  /** Enables the move arrows only when the selection can actually move in that direction. */
  private void updateMoveEnabled() {
    int row = table.getSelectedRow();
    moveUpAction.setEnabled(row > 0);
    moveDownAction.setEnabled(row >= 0 && row < profiles.size() - 1);
  }

  private static JButton textButton(String label, Runnable action) {
    return OxygenUIComponentsFactory.createButton(new AbstractAction(label) {
      @Override
      public void actionPerformed(ActionEvent e) {
        action.run();
      }
    });
  }

  private static JButton iconButton(String resource, String tooltip, Runnable action) {
    return OxygenUIComponentsFactory.createToolbarButton(iconAction(resource, tooltip, action), false);
  }

  private static Action iconAction(String resource, String tooltip, Runnable action) {
    URL url = ManageServersDialog.class.getResource(resource);
    return new AbstractAction() {
      {
        if (url != null) {
          putValue(SMALL_ICON, new ImageIcon(url));
        } else {
          putValue(NAME, tooltip);
        }
        putValue(SHORT_DESCRIPTION, tooltip);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        action.run();
      }
    };
  }

  private static void setDisabledIcon(JButton button, String resource) {
    URL url = ManageServersDialog.class.getResource(resource);
    if (url != null) {
      button.setDisabledIcon(new ImageIcon(url));
    }
  }

  /** Persists the working copy and the chosen default. */
  private void commit() {
    store.saveAll(profiles);
    store.setDefaultProfileId(defaultProfile != null ? defaultProfile.getId() : null);
  }

  private ConnectionProfile selected() {
    int row = table.getSelectedRow();
    return row >= 0 && row < profiles.size() ? profiles.get(row) : null;
  }

  private void selectByName(String name) {
    for (int i = 0; i < profiles.size(); i++) {
      if (profiles.get(i).getName().equals(name)) {
        table.setRowSelectionInterval(i, i);
        return;
      }
    }
  }

  private void add() {
    ConnectionProfile created = ConnectionDialog.edit(owner, new ConnectionProfile());
    if (created != null) {
      profiles.add(created);
      model.fireTableDataChanged();
      selectByName(created.getName());
    }
  }

  private void edit() {
    ConnectionProfile current = selected();
    if (current == null) {
      return;
    }
    int row = table.getSelectedRow();
    ConnectionProfile edited = ConnectionDialog.edit(owner, current);
    if (edited != null) {
      edited.setId(current.getId());
      profiles.set(row, edited);
      if (current == defaultProfile) {
        defaultProfile = edited;
      }
      model.fireTableDataChanged();
      selectByName(edited.getName());
    }
  }

  private void duplicate() {
    ConnectionProfile p = selected();
    if (p == null) {
      return;
    }
    ConnectionProfile copy = new ConnectionProfile(p.getName() + " copy", p.getBaseUrl(),
        p.getUser(), p.getPassword(), p.isAcceptSelfSigned());
    profiles.add(copy);
    model.fireTableDataChanged();
    selectByName(copy.getName());
  }

  private void remove() {
    ConnectionProfile p = selected();
    if (p == null) {
      return;
    }
    int choice = JOptionPane.showConfirmDialog(host, "Remove the server \"" + p.getName() + "\"?",
        "Remove server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
    if (choice == JOptionPane.OK_OPTION) {
      profiles.remove(p);
      if (p == defaultProfile) {
        defaultProfile = null;
      }
      model.fireTableDataChanged();
    }
  }

  private void move(int delta) {
    int from = table.getSelectedRow();
    int to = from + delta;
    if (from < 0 || to < 0 || to >= profiles.size()) {
      return;
    }
    profiles.add(to, profiles.remove(from));
    model.fireTableDataChanged();
    table.setRowSelectionInterval(to, to);
  }

  private void sort() {
    ConnectionProfile keep = selected();
    profiles.sort(Comparator.comparing(ConnectionProfile::getName, String.CASE_INSENSITIVE_ORDER));
    model.fireTableDataChanged();
    if (keep != null) {
      selectByName(keep.getName());
    }
  }

  private void setDefault() {
    ConnectionProfile p = selected();
    if (p != null) {
      defaultProfile = p;
      model.fireTableDataChanged();
    }
  }

  private void test() {
    ConnectionProfile p = selected();
    if (p == null) {
      return;
    }
    final ExistClient client = new ExistClient(p);
    final String name = p.getName();
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() throws Exception {
        client.systemInfo();
        return client.whoamiUser();
      }

      @Override
      protected void done() {
        try {
          JOptionPane.showMessageDialog(host,
              "Connected to \"" + name + "\". Authenticated as \"" + get() + "\".",
              "Test connection", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          JOptionPane.showMessageDialog(host, "Connection to \"" + name + "\" failed: "
              + cause.getMessage(), "Test connection", JOptionPane.ERROR_MESSAGE);
        }
      }
    }.execute();
    workspace.showStatusMessage("Testing connection to " + name + "…");
  }

  /** Table over the working profile list: Name, URL, and a check for the default server. */
  private final class ServerTableModel extends AbstractTableModel {
    private final String[] columns = {"Name", "URL", "Default"};

    @Override
    public int getRowCount() {
      return profiles.size();
    }

    @Override
    public int getColumnCount() {
      return columns.length;
    }

    @Override
    public String getColumnName(int column) {
      return columns[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
      ConnectionProfile p = profiles.get(row);
      return switch (column) {
        case 0 -> p.getName();
        case 1 -> p.getBaseUrl();
        default -> p == defaultProfile ? "✓" : "";
      };
    }
  }
}
