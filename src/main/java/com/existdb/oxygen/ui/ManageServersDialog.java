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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * A single window for managing saved eXist servers — a Data Source Explorer-style "Connections"
 * table (Name, URL, Default) with a flat icon toolbar for Add / Edit / Duplicate / Remove and
 * reordering (Move Up/Down, Sort A–Z), plus choosing the default server and testing a connection.
 * Edits are made on a working copy and persisted only on <b>OK</b>; <b>Cancel</b> (or Escape)
 * discards them.
 */
public final class ManageServersDialog extends JDialog {

  private final transient ProfileStore store;
  private final transient StandalonePluginWorkspace workspace;
  private final transient List<ConnectionProfile> profiles = new ArrayList<>();
  private final ServerTableModel model = new ServerTableModel();
  private final JTable table = new JTable(model);

  private transient ConnectionProfile defaultProfile;
  private boolean committed;

  private ManageServersDialog(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    super(owner, "Manage Servers", true);
    this.store = store;
    this.workspace = workspace;
    profiles.addAll(store.loadAll());
    String defaultId = store.defaultProfileId();
    for (ConnectionProfile p : profiles) {
      if (p.getId() != null && p.getId().equals(defaultId)) {
        defaultProfile = p;
      }
    }
    buildUi();
    if (!profiles.isEmpty()) {
      table.setRowSelectionInterval(0, 0);
    }
    setSize(720, 360);
    setLocationRelativeTo(owner);
  }

  /**
   * Opens the modal dialog. Returns {@code true} if the user committed changes (OK), so the caller
   * can refresh; {@code false} on Cancel/Escape.
   */
  public static boolean open(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    ManageServersDialog dialog = new ManageServersDialog(owner, store, workspace);
    dialog.setVisible(true);
    return dialog.committed;
  }

  private void buildUi() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    // Name compact, URL gets the room, Default a fixed narrow check column.
    table.getColumnModel().getColumn(0).setPreferredWidth(150);
    table.getColumnModel().getColumn(1).setPreferredWidth(430);
    table.getColumnModel().getColumn(2).setMaxWidth(60);
    table.getColumnModel().getColumn(2).setPreferredWidth(60);

    // App-specific actions on the left as text buttons.
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    left.add(textButton("Sort A–Z", this::sort));
    left.add(textButton("Set Default", this::setDefault));
    left.add(textButton("Test", this::test));

    // Oxygen Data Sources-style flat icon toolbar on the right.
    JToolBar right = new JToolBar();
    right.setFloatable(false);
    right.setRollover(true);
    right.add(iconButton("/images/Add16.png", "Add server…", this::add));
    right.add(iconButton("/images/Wrench16.png", "Edit server…", this::edit));
    right.add(iconButton("/images/Copy16.png", "Duplicate server", this::duplicate));
    right.add(iconButton("/images/Remove16.png", "Remove server…", this::remove));
    right.addSeparator();
    right.add(iconButton("/images/MoveUp16.png", "Move up", () -> move(-1)));
    right.add(iconButton("/images/MoveDown16.png", "Move down", () -> move(1)));

    JPanel actions = new JPanel(new BorderLayout());
    actions.add(left, BorderLayout.WEST);
    actions.add(right, BorderLayout.EAST);

    JPanel connections = new JPanel(new BorderLayout());
    connections.setBorder(BorderFactory.createTitledBorder("Connections"));
    connections.add(new JScrollPane(table), BorderLayout.CENTER);
    connections.add(actions, BorderLayout.SOUTH);

    JButton ok = new JButton("OK");
    ok.addActionListener(e -> commit());
    JButton cancel = new JButton("Cancel");
    cancel.addActionListener(e -> dispose());
    JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    south.add(ok);
    south.add(cancel);

    setLayout(new BorderLayout());
    add(connections, BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
    // Escape closes (cancel).
    getRootPane().registerKeyboardAction(e -> dispose(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
  }

  private static JButton textButton(String label, Runnable action) {
    JButton b = new JButton(label);
    b.setFocusable(false);
    b.addActionListener(e -> action.run());
    return b;
  }

  private static JButton iconButton(String resource, String tooltip, Runnable action) {
    JButton b = new JButton();
    URL url = ManageServersDialog.class.getResource(resource);
    if (url != null) {
      b.setIcon(new ImageIcon(url));
    } else {
      b.setText(tooltip);
    }
    b.setToolTipText(tooltip);
    b.setFocusable(false);
    b.addActionListener(e -> action.run());
    return b;
  }

  /** Persists the working copy and the chosen default, then closes. */
  private void commit() {
    store.saveAll(profiles);
    store.setDefaultProfileId(defaultProfile != null ? defaultProfile.getId() : null);
    committed = true;
    dispose();
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
    ConnectionProfile created = ConnectionDialog.edit(ownerFrame(), new ConnectionProfile());
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
    ConnectionProfile edited = ConnectionDialog.edit(ownerFrame(), current);
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
    int choice = JOptionPane.showConfirmDialog(this, "Remove the server \"" + p.getName() + "\"?",
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
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() throws Exception {
        client.systemInfo();
        return client.whoamiUser();
      }

      @Override
      protected void done() {
        try {
          JOptionPane.showMessageDialog(ManageServersDialog.this,
              "Connected. Authenticated as: " + get(), "Test connection",
              JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          JOptionPane.showMessageDialog(ManageServersDialog.this,
              "Connection failed: " + cause.getMessage(), "Test connection",
              JOptionPane.ERROR_MESSAGE);
        }
      }
    }.execute();
    workspace.showStatusMessage("Testing connection to " + p.getName() + "…");
  }

  private Frame ownerFrame() {
    return getOwner() instanceof Frame frame ? frame : null;
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
