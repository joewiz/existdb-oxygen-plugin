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

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * The content of the <b>Preferences → Plugins → eXist-db</b> option page — a Data Source
 * Explorer-style "Connections" table (Name, URL, Default) with a flat icon toolbar for Add / Edit /
 * Duplicate / Remove and reordering (Move Up/Down, Sort A–Z), plus the query/result and browsing
 * defaults. Edits are made on a working copy and persisted by {@link #save()} (called from the option
 * page's Apply/OK); {@link #buildContent()} builds the component and {@link #restoreDefaults()} resets
 * the display defaults. Driven by {@link ExistdbOptionPage}.
 */
public final class ExistdbPreferencesPanel {

  private static final String[] METHOD_LABELS = {"Adaptive", "JSON", "Text", "XML", "HTML5"};
  private static final String[] METHOD_VALUES = {"adaptive", "json", "text", "xml", "html5"};
  private static final Integer[] PAGE_SIZES = {10, 25, 50, 100};
  private final transient ProfileStore store;
  private final transient List<ConnectionProfile> profiles = new ArrayList<>();
  private final ServerTableModel model = new ServerTableModel();
  // Oxygen's table paints the alternating row stripes and selection used across the workbench.
  private final JTable table = OxygenUIComponentsFactory.createTable(model);
  private final JComboBox<String> methodPref =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(METHOD_LABELS));
  private final JComboBox<Integer> pageSizePref =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(PAGE_SIZES));
  private final JRadioButton destBrowse = new JRadioButton("eXist-db Results pane");
  private final JRadioButton destEditor = new JRadioButton("New editor window");
  private final JCheckBox indentPref = new JCheckBox("Indent");
  private final JCheckBox showHiddenPref = new JCheckBox("Show hidden files and directories");
  private final JCheckBox uploadHiddenPref = new JCheckBox("Upload hidden files and directories");
  private final JCheckBox restorePanePref =
      new JCheckBox("Restore open collections on startup");

  private transient ConnectionProfile defaultProfile;
  private transient Action moveUpAction;
  private transient Action moveDownAction;

  ExistdbPreferencesPanel(ProfileStore store) {
    this.store = store;
    profiles.addAll(store.loadAll());
    String defaultId = store.defaultProfileId();
    for (ConnectionProfile p : profiles) {
      if (p.getId() != null && p.getId().equals(defaultId)) {
        defaultProfile = p;
      }
    }
  }

  /** The Oxygen main window — the parent for the Add/Edit sub-dialogs (a Preferences page is in a
   *  JDialog, not a Frame, so the OK/Cancel dialog factory can't use it). */
  private static Frame mainFrame() {
    return (Frame) ((StandalonePluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace())
        .getParentFrame();
  }

  /** Builds the option-page component (connections table + the query/result and browsing defaults). */
  JComponent buildContent() {
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

    JComponent connectionsScroll = OxygenUIComponentsFactory.createScrollPane(table,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    // Fixed ~10-row height, like the Data Sources Connections table (not stretched to fill the page).
    int header = table.getTableHeader().getPreferredSize().height;
    connectionsScroll.setPreferredSize(new Dimension(680, table.getRowHeight() * 10 + header + 4));
    JPanel connections = new JPanel(new BorderLayout());
    connections.setBorder(boldTitledBorder("Connections"));
    connections.add(connectionsScroll, BorderLayout.CENTER);
    connections.add(actions, BorderLayout.SOUTH);

    table.getSelectionModel().addListSelectionListener(e -> updateMoveEnabled());
    updateMoveEnabled();

    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(buildResultPrefs(), BorderLayout.CENTER);
    bottom.add(buildBrowsingPrefs(), BorderLayout.SOUTH);

    // Stack connections over the defaults at the top; extra page height falls below (not into the
    // table) — so the Connections table keeps its fixed height like Oxygen's Data Sources page.
    JPanel stacked = new JPanel(new BorderLayout());
    stacked.add(connections, BorderLayout.NORTH);
    stacked.add(bottom, BorderLayout.CENTER);
    JPanel content = new JPanel(new BorderLayout());
    content.add(stacked, BorderLayout.NORTH);
    if (!profiles.isEmpty()) {
      table.setRowSelectionInterval(0, 0);
    }
    return content;
  }

  /** A titled border with a bold title, matching Oxygen's bold section headings. */
  private static TitledBorder boldTitledBorder(String title) {
    TitledBorder border = BorderFactory.createTitledBorder(title);
    Font base = border.getTitleFont();
    if (base == null) {
      base = new JLabel().getFont();
    }
    border.setTitleFont(base.deriveFont(Font.BOLD));
    return border;
  }

  /** Pane preferences: hidden-file handling, and restoring the open collections on startup. */
  private JComponent buildBrowsingPrefs() {
    showHiddenPref.setSelected(store.showHidden());
    uploadHiddenPref.setSelected(store.uploadHidden());
    restorePanePref.setSelected(store.restorePane());

    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.LINE_START;
    c.gridx = 0;

    JPanel hidden = new JPanel(new GridBagLayout());
    hidden.setBorder(boldTitledBorder("Hidden files"));
    c.gridy = 0;
    hidden.add(showHiddenPref, c);
    c.gridy = 1;
    hidden.add(uploadHiddenPref, c);

    JPanel pane = new JPanel(new GridBagLayout());
    pane.setBorder(boldTitledBorder("eXist-db pane"));
    c.gridy = 0;
    pane.add(restorePanePref, c);

    JPanel prefs = new JPanel(new BorderLayout());
    prefs.add(hidden, BorderLayout.NORTH);
    prefs.add(pane, BorderLayout.SOUTH);
    return prefs;
  }

  /**
   * The persisted query/result defaults (destination, serialization method, indent, page size).
   * Laid out with {@link GridBagLayout} anchored {@code BASELINE_LEADING} so each row's labels,
   * combos, and the (taller) Indent checkbox align on their text baseline — FlowLayout/BoxLayout
   * only center vertically, which drops the checkbox's baseline below the labels'.
   */
  private JComponent buildResultPrefs() {
    methodPref.setSelectedIndex(methodIndex(store.resultsMethod()));
    indentPref.setSelected(store.resultsIndent());
    pageSizePref.setSelectedItem(store.resultsPageSize());
    boolean toEditor = "editor".equals(store.resultsDestination());
    destEditor.setSelected(toEditor);
    destBrowse.setSelected(!toEditor);
    ButtonGroup destinationGroup = new ButtonGroup();
    destinationGroup.add(destBrowse);
    destinationGroup.add(destEditor);
    // Compact, uniform combo widths (matching the results view); 90px fits the widest value, "100".
    constrainWidth(methodPref, 120);
    constrainWidth(pageSizePref, 90);

    JPanel prefs = new JPanel(new GridBagLayout());
    prefs.setBorder(boldTitledBorder("Query & result defaults"));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.BASELINE_LEADING;

    c.gridy = 0;
    c.gridx = 0;
    prefs.add(new JLabel("View results in:"), c);
    c.gridx = 1;
    prefs.add(destBrowse, c);
    c.gridx = 2;
    c.gridwidth = 3;
    prefs.add(destEditor, c);
    c.gridwidth = 1;

    c.gridy = 1;
    c.gridx = 0;
    prefs.add(new JLabel("Serialization:"), c);
    c.gridx = 1;
    prefs.add(methodPref, c);
    c.gridx = 2;
    prefs.add(indentPref, c);
    c.gridx = 3;
    prefs.add(new JLabel("Results per page:"), c);
    c.gridx = 4;
    prefs.add(pageSizePref, c);
    return prefs;
  }

  /** Pins a component to a fixed width (its preferred height kept) so combos sit at a uniform size. */
  private static void constrainWidth(JComponent component, int width) {
    Dimension size = new Dimension(width, component.getPreferredSize().height);
    component.setPreferredSize(size);
    component.setMaximumSize(size);
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
    URL url = ExistdbPreferencesPanel.class.getResource(resource);
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
    URL url = ExistdbPreferencesPanel.class.getResource(resource);
    if (url != null) {
      button.setDisabledIcon(new ImageIcon(url));
    }
  }

  /** Persists the working copy, the chosen default, and the display defaults (option-page Apply/OK). */
  void save() {
    store.saveAll(profiles);
    store.setDefaultProfileId(defaultProfile != null ? defaultProfile.getId() : null);
    store.setResultsMethod(METHOD_VALUES[methodPref.getSelectedIndex()]);
    store.setResultsIndent(indentPref.isSelected());
    store.setResultsPageSize((Integer) pageSizePref.getSelectedItem());
    store.setResultsDestination(destEditor.isSelected() ? "editor" : "browse");
    store.setShowHidden(showHiddenPref.isSelected());
    store.setUploadHidden(uploadHiddenPref.isSelected());
    store.setRestorePane(restorePanePref.isSelected());
    // Apply the new defaults live: results view re-applies display prefs; the pane rebuilds servers.
    store.notifyResultsPrefsChanged();
    store.notifyConnectionsChanged();
  }

  /** Resets the display/browsing defaults to their factory values (connections are left untouched). */
  void restoreDefaults() {
    methodPref.setSelectedIndex(methodIndex("adaptive"));
    indentPref.setSelected(true);
    pageSizePref.setSelectedItem(10);
    destBrowse.setSelected(true);
    showHiddenPref.setSelected(false);
    uploadHiddenPref.setSelected(false);
    restorePanePref.setSelected(true);
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
    ConnectionProfile created = ConnectionDialog.edit(mainFrame(), new ConnectionProfile());
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
    ConnectionProfile edited = ConnectionDialog.edit(mainFrame(), current);
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
    int choice = PluginWorkspaceProvider.getPluginWorkspace().showConfirmDialog(
        "Remove server", "Remove the server \"" + p.getName() + "\"?",
        new String[] {"Remove", "Cancel"}, new int[] {0, 1});
    if (choice == 0) {
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
