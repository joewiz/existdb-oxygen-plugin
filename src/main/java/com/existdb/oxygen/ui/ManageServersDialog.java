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

import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;

import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * A single window for managing saved eXist servers — a Data Source Explorer-style "Connections"
 * table (Name, URL, Default) with a flat icon toolbar for Add / Edit / Duplicate / Remove and
 * reordering (Move Up/Down, Sort A–Z), plus choosing the default server and testing a connection.
 * Hosted in Oxygen's {@link OKCancelDialog}; edits are made on a working copy and persisted only on
 * <b>OK</b>; <b>Cancel</b> (or Escape) discards them.
 */
public final class ManageServersDialog {

  private static final String[] METHOD_LABELS = {"Adaptive", "JSON", "Text", "XML", "HTML5"};
  private static final String[] METHOD_VALUES = {"adaptive", "json", "text", "xml", "html5"};
  private static final Integer[] PAGE_SIZES = {10, 25, 50, 100};

  private final transient Frame owner;
  private final transient ProfileStore store;
  private final transient List<ConnectionProfile> profiles = new ArrayList<>();
  private final ServerTableModel model = new ServerTableModel();
  // Oxygen's table paints the alternating row stripes and selection used across the workbench.
  private final JTable table = OxygenUIComponentsFactory.createTable(model);
  private final JComboBox<String> methodPref =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(METHOD_LABELS));
  private final JComboBox<Integer> pageSizePref =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(PAGE_SIZES));
  private final JCheckBox indentPref = new JCheckBox("Indent");

  private transient ConnectionProfile defaultProfile;
  private transient OKCancelDialog host;
  private transient Action moveUpAction;
  private transient Action moveDownAction;

  private ManageServersDialog(Frame owner, ProfileStore store) {
    this.owner = owner;
    this.store = store;
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
  public static boolean open(Frame owner, ProfileStore store) {
    ManageServersDialog controller = new ManageServersDialog(owner, store);
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
    table.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (e.getClickCount() == 2 && row >= 0) {
          edit();
        } else if (row >= 0 && table.columnAtPoint(e.getPoint()) == 2) {
          setDefaultRow(row); // click the Default radio to choose the default server
        }
      }
    });
    table.setFillsViewportHeight(true);
    // Name compact, URL gets the room, Default a fixed narrow radio column.
    table.getColumnModel().getColumn(0).setPreferredWidth(150);
    table.getColumnModel().getColumn(1).setPreferredWidth(430);
    table.getColumnModel().getColumn(2).setMaxWidth(60);
    table.getColumnModel().getColumn(2).setPreferredWidth(60);
    table.getColumnModel().getColumn(2).setCellRenderer(radioRenderer());

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
    right.addSeparator();
    right.add(iconButton("/images/Sort16.png", "Sort A–Z", this::sort));

    JPanel actions = new JPanel(new BorderLayout());
    actions.add(right, BorderLayout.EAST);

    JPanel connections = new JPanel(new BorderLayout());
    connections.setBorder(BorderFactory.createTitledBorder("Connections"));
    connections.add(OxygenUIComponentsFactory.createScrollPane(table,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
    connections.add(actions, BorderLayout.SOUTH);

    table.getSelectionModel().addListSelectionListener(e -> updateMoveEnabled());
    updateMoveEnabled();

    JPanel content = new JPanel(new BorderLayout());
    content.add(connections, BorderLayout.CENTER);
    content.add(buildResultPrefs(), BorderLayout.SOUTH);
    return content;
  }

  /** The persisted result-display defaults (serialization method, indent, page size). */
  private JComponent buildResultPrefs() {
    methodPref.setSelectedIndex(methodIndex(store.resultsMethod()));
    indentPref.setSelected(store.resultsIndent());
    pageSizePref.setSelectedItem(store.resultsPageSize());
    JPanel prefs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    prefs.setBorder(BorderFactory.createTitledBorder("Result display defaults"));
    prefs.add(new JLabel("Serialization:"));
    prefs.add(methodPref);
    prefs.add(indentPref);
    prefs.add(new JLabel("Results per page:"));
    prefs.add(pageSizePref);
    return prefs;
  }

  private static int methodIndex(String method) {
    for (int i = 0; i < METHOD_VALUES.length; i++) {
      if (METHOD_VALUES[i].equals(method)) {
        return i;
      }
    }
    return 0;
  }

  /** Renders the Default column as a centered radio button reflecting the chosen default server. */
  private TableCellRenderer radioRenderer() {
    return (table, value, selected, focused, row, column) -> {
      JRadioButton radio = new JRadioButton();
      radio.setHorizontalAlignment(SwingConstants.CENTER);
      radio.setSelected(Boolean.TRUE.equals(value));
      radio.setOpaque(true);
      radio.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
      return radio;
    };
  }

  private void setDefaultRow(int row) {
    if (row >= 0 && row < profiles.size()) {
      defaultProfile = profiles.get(row);
      model.fireTableDataChanged();
    }
  }

  /** Enables the move arrows only when the selection can actually move in that direction. */
  private void updateMoveEnabled() {
    int row = table.getSelectedRow();
    moveUpAction.setEnabled(row > 0);
    moveDownAction.setEnabled(row >= 0 && row < profiles.size() - 1);
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

  /** Persists the working copy, the chosen default, and the result-display defaults. */
  private void commit() {
    store.saveAll(profiles);
    store.setDefaultProfileId(defaultProfile != null ? defaultProfile.getId() : null);
    store.setResultsMethod(METHOD_VALUES[methodPref.getSelectedIndex()]);
    store.setResultsIndent(indentPref.isSelected());
    store.setResultsPageSize((Integer) pageSizePref.getSelectedItem());
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
        default -> p == defaultProfile; // Boolean: drives the Default-column radio
      };
    }
  }
}
