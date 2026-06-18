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
import ro.sync.exml.workspace.api.standalone.ui.Table;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
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
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableCellRenderer;

/**
 * The content of the <b>Preferences → Plugins → eXist-db</b> option page — a Data Source
 * Explorer-style "Connections" table (Name, URL, User, Default) with a flat icon toolbar for Add /
 * Edit / Duplicate / Remove and reordering (Move Up/Down, Sort A–Z), plus the query/result and browsing
 * defaults. Edits are made on a working copy and persisted by {@link #save()} (called from the option
 * page's Apply/OK); {@link #buildContent()} builds the component and {@link #restoreDefaults()} resets
 * the display defaults. Driven by {@link ExistdbOptionPage}.
 */
public final class ExistdbPreferencesPanel {

  private static final String[] METHOD_LABELS = {"Adaptive", "JSON", "Text", "XML", "HTML5"};
  private static final String[] METHOD_VALUES = {"adaptive", "json", "text", "xml", "html5"};
  private static final Integer[] PAGE_SIZES = {10, 25, 50, 100};
  /** The Connections-section description (HTML-escaped; {@code >} → {@code &gt;}). */
  private static final String CONNECTIONS_DESCRIPTION = "This plugin requires at least one connection "
      + "to a compatible eXist-db server. The plugin communicates with the server's API to provide "
      + "all of its capabilities: browsing and editing database resources, assisting you with writing "
      + "XQuery (function documentation lookup, code completion, parameter hints, and go-to-definition "
      + "for functions), and validating and executing XQuery. To activate validation, navigate to "
      + "Preferences &gt; XML &gt; XSLT-XQuery &gt; XQuery and in the \"Validation engine\" dropdown "
      + "menu, select \"eXist-db (HTTP)\".";
  private final transient ProfileStore store;
  private final transient List<ConnectionProfile> profiles = new ArrayList<>();
  private final ServerTableModel model = new ServerTableModel();
  // Oxygen's table for native selection/look; subclassed to force stripes and extend them into the
  // empty area below the last row (matching the Data Sources tables).
  private final JTable table = new StripedTable(model);
  private final JComboBox<String> methodPref =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(METHOD_LABELS));
  private final JComboBox<Integer> pageSizePref =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(PAGE_SIZES));
  private final JRadioButton destBrowse = new JRadioButton("eXist-db Results pane");
  private final JRadioButton destEditor = new JRadioButton("New editor window");
  private final JCheckBox indentPref = new JCheckBox("Indent results");
  private final JCheckBox wrapPref = new JCheckBox("Line wrap");
  private final JCheckBox showHiddenPref = new JCheckBox("Show hidden files and directories");
  private final JCheckBox uploadHiddenPref = new JCheckBox("Upload hidden files and directories");
  private final JCheckBox restorePanePref =
      new JCheckBox("Restore open collections on startup");
  private final JCheckBox reopenTabsPref =
      new JCheckBox("Reopen eXist-db editor tabs on restart");
  // Serialization matrix: [Open, Download] × [Indent, Expand XIncludes, Omit XML Declaration].
  private final JCheckBox openIndentPref = new JCheckBox();
  private final JCheckBox openExpandPref = new JCheckBox();
  private final JCheckBox openOmitPref = new JCheckBox();
  private final JCheckBox downloadIndentPref = new JCheckBox();
  private final JCheckBox downloadExpandPref = new JCheckBox();
  private final JCheckBox downloadOmitPref = new JCheckBox();
  private final JCheckBox packageIndentPref = new JCheckBox();
  private final JCheckBox packageExpandPref = new JCheckBox();
  private final JCheckBox packageOmitPref = new JCheckBox();
  /** Connections-section description; its HTML wrap width tracks its actual width (auto-flow). */
  private final JLabel connectionsNote = new JLabel();

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
        } else if (row >= 0 && table.columnAtPoint(e.getPoint()) == 3) {
          setDefaultRow(row); // click the Default radio to choose the default server
        }
      }
    });
    table.setFillsViewportHeight(true);
    // Name compact, URL gets the room, User a narrow column, Default a fixed narrow radio column.
    table.getColumnModel().getColumn(0).setPreferredWidth(150);
    table.getColumnModel().getColumn(1).setPreferredWidth(330);
    table.getColumnModel().getColumn(2).setPreferredWidth(100);
    table.getColumnModel().getColumn(3).setMaxWidth(60);
    table.getColumnModel().getColumn(3).setPreferredWidth(60);
    table.getColumnModel().getColumn(3).setCellRenderer(radioRenderer());

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
    // Fixed ~3-row height (scrolls beyond) — the connections table is the cheapest control to cap, so
    // the whole page fits a 14" laptop screen at default resolution without vertical truncation.
    int header = table.getTableHeader().getPreferredSize().height;
    connectionsScroll.setPreferredSize(new Dimension(680, table.getRowHeight() * 3 + header + 4));
    JPanel connectionsContent = new JPanel(new BorderLayout());
    connectionsContent.add(connectionsDescription(), BorderLayout.NORTH);
    connectionsContent.add(connectionsScroll, BorderLayout.CENTER);
    connectionsContent.add(actions, BorderLayout.SOUTH);
    JComponent connections = section("Connections", connectionsContent);

    table.getSelectionModel().addListSelectionListener(e -> updateMoveEnabled());
    updateMoveEnabled();

    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(buildResultPrefs(), BorderLayout.NORTH);
    JPanel lower = new JPanel(new BorderLayout());
    lower.add(buildSerializationPrefs(), BorderLayout.NORTH);
    lower.add(buildBrowsingPrefs(), BorderLayout.CENTER);
    bottom.add(lower, BorderLayout.CENTER);

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

  /**
   * A group with Oxygen's Preferences section style: a bold title with a horizontal rule extending to
   * the right (no enclosing box), and {@code content} below it, indented under the header.
   */
  private static JComponent section(String title, JComponent content) {
    JLabel label = new JLabel(title);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    JPanel headerRow = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    headerRow.add(label, c);
    c.gridx = 1;
    c.weightx = 1.0;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(0, 8, 0, 0);
    headerRow.add(new JSeparator(), c);

    content.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
    JPanel group = new JPanel(new BorderLayout());
    group.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
    group.add(headerRow, BorderLayout.NORTH);
    group.add(content, BorderLayout.CENTER);
    return group;
  }

  /**
   * The Oxygen connections table, custom-striped: data rows are striped via {@code prepareRenderer}
   * and the empty area below the last row is striped in {@code paintComponent}, so the whole table
   * matches the Data Sources tables (the factory table stripes only data rows, if at all).
   */
  private static final class StripedTable extends Table {
    StripedTable(TableModel tableModel) {
      super(tableModel);
    }

    @Override
    public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
      Component comp = super.prepareRenderer(renderer, row, column);
      if (!isRowSelected(row)) {
        comp.setBackground(stripeColor(row));
      }
      return comp;
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      int rowHeight = getRowHeight();
      if (rowHeight <= 0) {
        return;
      }
      int firstEmpty = getRowCount();
      int y = firstEmpty * rowHeight;
      for (int row = firstEmpty; y < getHeight(); row++, y += rowHeight) {
        g.setColor(stripeColor(row));
        g.fillRect(0, y, getWidth(), rowHeight);
      }
    }

    private Color stripeColor(int row) {
      Color bg = getBackground();
      if (row % 2 == 0) {
        return bg;
      }
      boolean dark = bg.getRed() + bg.getGreen() + bg.getBlue() < 384;
      return dark ? bg.brighter() : new Color(0xED, 0xF2, 0xFB);
    }
  }

  /** Pane preferences: hidden-file handling, and restoring the open collections on startup. */
  private JComponent buildBrowsingPrefs() {
    showHiddenPref.setSelected(store.showHidden());
    uploadHiddenPref.setSelected(store.uploadHidden());
    restorePanePref.setSelected(store.restorePane());
    reopenTabsPref.setSelected(store.reopenTabs());

    JComponent hidden = section("Hidden files", leftAlignedColumn(showHiddenPref, uploadHiddenPref));
    JComponent pane =
        section("eXist-db pane", leftAlignedColumn(restorePanePref, reopenTabsPref));

    JPanel prefs = new JPanel(new BorderLayout());
    prefs.add(hidden, BorderLayout.NORTH);
    prefs.add(pane, BorderLayout.SOUTH);
    return prefs;
  }

  /**
   * The serialization matrix: how XML resources are serialized by existdb-openapi when read, per
   * operation — Open (load into the editor) vs. Download (save/export to disk) — across Indent,
   * Expand XIncludes, and Omit XML Declaration. Defaults mirror eXide: indent on, expand-xincludes
   * off (preserve {@code <xi:include>}), omit-xml-declaration on.
   */
  private JComponent buildSerializationPrefs() {
    openIndentPref.setSelected(store.serializationFlag("open", "indent", true));
    openExpandPref.setSelected(store.serializationFlag("open", "expand-xincludes", false));
    openOmitPref.setSelected(store.serializationFlag("open", "omit-xml-declaration", true));
    downloadIndentPref.setSelected(store.serializationFlag("download", "indent", true));
    downloadExpandPref.setSelected(store.serializationFlag("download", "expand-xincludes", false));
    downloadOmitPref.setSelected(store.serializationFlag("download", "omit-xml-declaration", true));
    packageIndentPref.setSelected(store.serializationFlag("package", "indent", false));
    packageExpandPref.setSelected(store.serializationFlag("package", "expand-xincludes", false));
    packageOmitPref.setSelected(store.serializationFlag("package", "omit-xml-declaration", true));

    JPanel grid = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 8, 2, 8);
    c.gridy = 0;
    c.anchor = GridBagConstraints.CENTER;
    c.gridx = 1;
    grid.add(columnHeader("Open"), c);
    c.gridx = 2;
    grid.add(columnHeader("Download"), c);
    c.gridx = 3;
    grid.add(columnHeader("Package"), c);
    serializationRow(grid, c, 1, "Indent", openIndentPref, downloadIndentPref, packageIndentPref);
    serializationRow(grid, c, 2, "Expand XIncludes",
        openExpandPref, downloadExpandPref, packageExpandPref);
    serializationRow(grid, c, 3, "Omit XML Declaration",
        openOmitPref, downloadOmitPref, packageOmitPref);
    return section("Loading documents from eXist-db", grid);
  }

  private static void serializationRow(JPanel grid, GridBagConstraints c, int row, String label,
      JCheckBox open, JCheckBox download, JCheckBox pkg) {
    c.gridy = row;
    c.gridx = 0;
    c.anchor = GridBagConstraints.WEST;
    grid.add(new JLabel(label), c);
    c.anchor = GridBagConstraints.CENTER;
    c.gridx = 1;
    grid.add(open, c);
    c.gridx = 2;
    grid.add(download, c);
    c.gridx = 3;
    grid.add(pkg, c);
  }

  private static JLabel columnHeader(String text) {
    JLabel label = new JLabel(text);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    return label;
  }

  /**
   * The Connections-section description: what these connections are used for. Sits below the
   * "Connections" heading and above the table (the section-description pattern Oxygen uses, e.g. the
   * Batch Documents Converter's "Word styles mapping"). Mentions XQuery validation as one use, naming
   * the built-in menu path since the SDK offers no way to deep-link to (or set) that control.
   */
  private JComponent connectionsDescription() {
    connectionsNote.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
    reflowConnectionsNote();
    // Re-wrap to the container's width whenever it changes, so the text auto-flows instead of
    // wrapping at a hardcoded column. (Same approach as SearchDialog's intro line.)
    connectionsNote.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        reflowConnectionsNote();
      }
    });
    // The componentResized pass above can run before the page settles at its final width, leaving the
    // paragraph wrapped narrow until the user resizes. Re-flow once the page is actually shown (after
    // layout has settled) so it fills the width on first display too.
    connectionsNote.addHierarchyListener(e -> {
      if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && connectionsNote.isShowing()) {
        SwingUtilities.invokeLater(this::reflowConnectionsNote);
      }
    });
    return connectionsNote;
  }

  /**
   * Re-renders the connections description at a wrap width matching the label's current width. A
   * table cell with an explicit {@code width} is the constraint Swing's {@code JLabel} HTML honors;
   * binding it to the live width (rather than a fixed column) lets the paragraph reflow on resize.
   */
  private void reflowConnectionsNote() {
    // Wrap to the *container's* width, not the label's own — binding to the label's width feeds back
    // through the HTML table width into its preferred width and settles wide enough that the whole
    // paragraph "fits" on one line, so NORTH allocates one line's height and clips the rest.
    Container parent = connectionsNote.getParent();
    int width = parent != null ? parent.getWidth() : 0;
    if (width < 120) {
      width = 560; // before the first layout pass, fall back to a sensible column
    }
    connectionsNote.setText("<html><table><tr><td width='" + (width - 8) + "'>"
        + CONNECTIONS_DESCRIPTION + "</td></tr></table></html>");
  }

  /** A left-aligned vertical column of the given components (e.g. checkboxes flush to the left). */
  private static JComponent leftAlignedColumn(JComponent... items) {
    JPanel column = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.anchor = GridBagConstraints.LINE_START;
    c.insets = new Insets(1, 0, 1, 0);
    for (int i = 0; i < items.length; i++) {
      c.gridy = i;
      column.add(items[i], c);
    }
    // A glue cell to the right absorbs the extra width so the items stay flush left (not centered).
    c.gridx = 1;
    c.gridy = 0;
    c.weightx = 1.0;
    c.fill = GridBagConstraints.HORIZONTAL;
    column.add(new JPanel(), c);
    return column;
  }

  /**
   * The persisted query/result defaults: a lead "View results in:" row (the results destination),
   * then a "Results pane defaults" section laying out the serialization method, page size, and the
   * Indent/Line-wrap toggles vertically (one field per row) like Oxygen's own preference pages.
   */
  private JComponent buildResultPrefs() {
    methodPref.setSelectedIndex(methodIndex(store.resultsMethod()));
    indentPref.setSelected(store.resultsIndent());
    wrapPref.setSelected(store.resultsWrap());
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

    // "View results in:" — both radios side by side on one row, above the Results-pane defaults.
    JPanel destRow = new JPanel(new GridBagLayout());
    GridBagConstraints d = new GridBagConstraints();
    d.insets = new Insets(2, 4, 2, 4);
    d.anchor = GridBagConstraints.LINE_START;
    d.gridx = 0;
    destRow.add(new JLabel("View results in:"), d);
    d.gridx = 1;
    destRow.add(destBrowse, d);
    d.gridx = 2;
    destRow.add(destEditor, d);
    d.gridx = 3;
    d.weightx = 1.0;
    d.fill = GridBagConstraints.HORIZONTAL;
    destRow.add(new JPanel(), d);

    // "Results pane defaults": serialization method, page size, then the boolean toggles.
    JPanel prefs = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.LINE_START;
    c.gridy = 0;
    c.gridx = 0;
    prefs.add(new JLabel("Serialization method:"), c);
    c.gridx = 1;
    prefs.add(methodPref, c);
    c.gridy = 1;
    c.gridx = 0;
    prefs.add(new JLabel("Results per page:"), c);
    c.gridx = 1;
    prefs.add(pageSizePref, c);
    c.gridx = 0;
    c.gridwidth = 2;
    c.gridy = 2;
    prefs.add(indentPref, c);
    c.gridy = 3;
    prefs.add(wrapPref, c);
    c.gridwidth = 1;
    // A glue cell to the right keeps the grid flush left instead of centered in the page width.
    c.gridx = 3;
    c.gridy = 0;
    c.weightx = 1.0;
    c.fill = GridBagConstraints.HORIZONTAL;
    prefs.add(new JPanel(), c);

    JPanel container = new JPanel(new BorderLayout());
    container.add(destRow, BorderLayout.NORTH);
    container.add(section("Results pane defaults", prefs), BorderLayout.CENTER);
    return container;
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
    // Decide up front whether the eXist-db pane needs rebuilding: only when the connections
    // themselves change, or when hidden-file visibility (which the pane displays) flips. Otherwise a
    // mere serialization/results pref edit would rebuild the tree and collapse the open collections.
    boolean connectionsChanged = !connectionsSignature(store.loadAll(),
        store.defaultProfileId()).equals(connectionsSignature(profiles,
        defaultProfile != null ? defaultProfile.getId() : null));
    boolean showHiddenChanged = showHiddenPref.isSelected() != store.showHidden();

    store.saveAll(profiles);
    store.setDefaultProfileId(defaultProfile != null ? defaultProfile.getId() : null);
    store.setResultsMethod(METHOD_VALUES[methodPref.getSelectedIndex()]);
    store.setResultsIndent(indentPref.isSelected());
    store.setResultsWrap(wrapPref.isSelected());
    store.setResultsPageSize((Integer) pageSizePref.getSelectedItem());
    store.setResultsDestination(destEditor.isSelected() ? "editor" : "browse");
    store.setShowHidden(showHiddenPref.isSelected());
    store.setUploadHidden(uploadHiddenPref.isSelected());
    store.setRestorePane(restorePanePref.isSelected());
    store.setReopenTabs(reopenTabsPref.isSelected());
    store.setSerializationFlag("open", "indent", openIndentPref.isSelected());
    store.setSerializationFlag("open", "expand-xincludes", openExpandPref.isSelected());
    store.setSerializationFlag("open", "omit-xml-declaration", openOmitPref.isSelected());
    store.setSerializationFlag("download", "indent", downloadIndentPref.isSelected());
    store.setSerializationFlag("download", "expand-xincludes", downloadExpandPref.isSelected());
    store.setSerializationFlag("download", "omit-xml-declaration", downloadOmitPref.isSelected());
    store.setSerializationFlag("package", "indent", packageIndentPref.isSelected());
    store.setSerializationFlag("package", "expand-xincludes", packageExpandPref.isSelected());
    store.setSerializationFlag("package", "omit-xml-declaration", packageOmitPref.isSelected());
    // Results view always re-applies display prefs (cheap, doesn't touch the pane). The pane only
    // rebuilds when its contents actually changed — so editing serialization/results prefs keeps the
    // open collections expanded.
    store.notifyResultsPrefsChanged();
    if (connectionsChanged || showHiddenChanged) {
      store.notifyConnectionsChanged();
    }
  }

  /** A stable signature of the connection set (and default), to detect whether it changed on save. */
  private static String connectionsSignature(List<ConnectionProfile> profiles, String defaultId) {
    StringBuilder sb = new StringBuilder("default=").append(defaultId).append('\n');
    for (ConnectionProfile p : profiles) {
      sb.append(p.getId()).append('|').append(p.getName()).append('|').append(p.getBaseUrl())
          .append('|').append(p.getUser()).append('|').append(p.isAcceptSelfSigned()).append('\n');
    }
    return sb.toString();
  }

  /** Resets the display/browsing defaults to their factory values (connections are left untouched). */
  void restoreDefaults() {
    methodPref.setSelectedIndex(methodIndex("adaptive"));
    indentPref.setSelected(true);
    wrapPref.setSelected(true);
    pageSizePref.setSelectedItem(10);
    destBrowse.setSelected(true);
    showHiddenPref.setSelected(false);
    uploadHiddenPref.setSelected(false);
    restorePanePref.setSelected(true);
    reopenTabsPref.setSelected(true);
    openIndentPref.setSelected(true);
    openExpandPref.setSelected(false);
    openOmitPref.setSelected(true);
    downloadIndentPref.setSelected(true);
    downloadExpandPref.setSelected(false);
    downloadOmitPref.setSelected(true);
    packageIndentPref.setSelected(false);
    packageExpandPref.setSelected(false);
    packageOmitPref.setSelected(true);
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

  /** Table over the working profile list: Name, URL, User, and a check for the default server. */
  private final class ServerTableModel extends AbstractTableModel {
    private final String[] columns = {"Name", "URL", "User", "Default"};

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
        case 2 -> p.getUser();
        default -> p == defaultProfile; // Boolean: drives the Default-column radio
      };
    }
  }
}
