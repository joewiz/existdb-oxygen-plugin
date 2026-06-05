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
import java.awt.Frame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * A single window for managing saved eXist servers — a Data Source Explorer-style table (Name, URL,
 * Default) with a toolbar for Add / Edit / Duplicate / Remove, reordering (Move Up/Down, Sort A–Z),
 * choosing the default server, and testing a connection. Every action applies immediately to the
 * {@link ProfileStore}; the eXist-db pane reloads its tree after the dialog closes.
 */
public final class ManageServersDialog extends JDialog {

  private final transient ProfileStore store;
  private final transient StandalonePluginWorkspace workspace;
  private final transient List<ConnectionProfile> profiles = new ArrayList<>();
  private final ServerTableModel model = new ServerTableModel();
  private final JTable table = new JTable(model);

  private ManageServersDialog(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    super(owner, "Manage Servers", true);
    this.store = store;
    this.workspace = workspace;
    buildUi();
    reload();
    if (!profiles.isEmpty()) {
      table.setRowSelectionInterval(0, 0);
    }
    setSize(660, 360);
    setLocationRelativeTo(owner);
  }

  /** Opens the modal dialog; changes are persisted to {@code store} as they are made. */
  public static void open(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    new ManageServersDialog(owner, store, workspace).setVisible(true);
  }

  private void buildUi() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getColumnModel().getColumn(2).setMaxWidth(70);

    JToolBar bar = new JToolBar();
    bar.setFloatable(false);
    bar.add(button("Add…", this::add));
    bar.add(button("Edit…", this::edit));
    bar.add(button("Duplicate", this::duplicate));
    bar.add(button("Remove…", this::remove));
    bar.addSeparator();
    bar.add(button("Move Up", () -> move(-1)));
    bar.add(button("Move Down", () -> move(1)));
    bar.add(button("Sort A–Z", this::sort));
    bar.addSeparator();
    bar.add(button("Set Default", this::setDefault));
    bar.add(button("Test", this::test));

    JButton close = new JButton("Close");
    close.addActionListener(e -> dispose());
    JPanel south = new JPanel();
    south.add(close);

    setLayout(new BorderLayout());
    add(bar, BorderLayout.NORTH);
    add(new JScrollPane(table), BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(close);
  }

  private static JButton button(String label, Runnable action) {
    JButton b = new JButton(label);
    b.setFocusable(false);
    b.addActionListener(e -> action.run());
    return b;
  }

  private void reload() {
    profiles.clear();
    profiles.addAll(store.loadAll());
    model.fireTableDataChanged();
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
      store.saveAll(profiles);
      reload();
      selectByName(created.getName());
    }
  }

  private void edit() {
    ConnectionProfile current = selected();
    if (current == null) {
      return;
    }
    ConnectionProfile edited = ConnectionDialog.edit(ownerFrame(), current);
    if (edited != null) {
      edited.setId(current.getId());
      profiles.set(table.getSelectedRow(), edited);
      store.saveAll(profiles);
      reload();
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
    store.saveAll(profiles);
    reload();
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
      store.saveAll(profiles);
      reload();
    }
  }

  private void move(int delta) {
    int from = table.getSelectedRow();
    int to = from + delta;
    if (from < 0 || to < 0 || to >= profiles.size()) {
      return;
    }
    profiles.add(to, profiles.remove(from));
    store.saveAll(profiles);
    reload();
    table.setRowSelectionInterval(to, to);
  }

  private void sort() {
    ConnectionProfile keep = selected();
    profiles.sort(Comparator.comparing(ConnectionProfile::getName, String.CASE_INSENSITIVE_ORDER));
    store.saveAll(profiles);
    reload();
    if (keep != null) {
      selectByName(keep.getName());
    }
  }

  private void setDefault() {
    ConnectionProfile p = selected();
    if (p != null && p.getId() != null) {
      store.setDefaultProfileId(p.getId());
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
        default -> p.getId() != null && p.getId().equals(store.defaultProfileId()) ? "✓" : "";
      };
    }
  }
}
