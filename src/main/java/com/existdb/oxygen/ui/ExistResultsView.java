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
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.query.QueryRunner;

import org.json.JSONArray;
import org.json.JSONObject;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * An eXide-style query-results view: numbered, striped result rows with per-result copy, pagination
 * (10/25/50/100 per page; first/prev/next/last), a serialization-method dropdown
 * (Adaptive/JSON/Text/XML/HTML5), an indent toggle, and a compile/eval/total/items metrics bar.
 *
 * <p>This is the opt-in second results mode (the default remains "open results in a new editor").
 * Paging and serialization map directly onto the cursor API: the server cursor stays open and pages
 * are fetched on demand with {@code start}/{@code count}/{@code method}/{@code indent}.
 */
public final class ExistResultsView extends JPanel {

  private static final String[] METHOD_LABELS = {"Adaptive", "JSON", "Text", "XML", "HTML5"};
  private static final String[] METHOD_VALUES = {"adaptive", "json", "text", "xml", "html5"};
  private static final Integer[] PAGE_SIZES = {10, 25, 50, 100};
  private static final int DEFAULT_FONT_SIZE = 12;
  private static final int MIN_FONT_SIZE = 7;
  private static final int MAX_FONT_SIZE = 36;
  // When wrapping, a row's pane is bounded to (viewport width - chrome): the number cell, the row's
  // BorderLayout gap, and its left/right insets. Never wrap narrower than MIN_WRAP_WIDTH.
  private static final int WRAP_CHROME = 66;
  private static final int MIN_WRAP_WIDTH = 120;
  // Solid (non-alpha) colors: translucent backgrounds on opaque rows leave paint artifacts.
  private static final Color STRIPE = new Color(0xF2, 0xF4, 0xF7);
  private static final Color PLAIN = Color.WHITE;
  private static final Color SELECTION = new Color(0xD6, 0xE6, 0xFB);

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;

  private final JComboBox<String> methodCombo =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(METHOD_LABELS));
  private final JComboBox<Integer> pageSizeCombo =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(PAGE_SIZES));
  private final JButton indentButton;
  private final JButton highlightButton;
  private final JButton wrapButton;
  private final JButton firstButton;
  private final JButton prevButton;
  private final JButton prevItemButton;
  private final JButton nextItemButton;
  private final JButton nextButton;
  private final JButton lastButton;
  private final JLabel rangeLabel = new JLabel("No results");
  private final JLabel metricsLabel = new JLabel(" ");
  private final JPanel rows = new JPanel();
  private final transient List<JPanel> rowPanels = new ArrayList<>();
  /** The result-text panes, parallel to {@link #rowPanels}, restyled on zoom. */
  private final transient List<JTextPane> rowTextPanes = new ArrayList<>();
  private final transient List<QueryRunner.Item> pageItems = new ArrayList<>();
  private final JScrollPane scrollPane = OxygenUIComponentsFactory.createScrollPane(rows,
      ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
  private final JLayeredPane layered = new JLayeredPane();
  /** One copy button per visible result row, floated at the viewport's right edge (parallel to
   *  {@link #rowPanels}). */
  private final transient List<JButton> rowCopyButtons = new ArrayList<>();
  /** One "open source" button per visible result row, floated just left of the copy button (parallel
   *  to {@link #rowPanels}); the entry stays hidden for results that have no stored source node. */
  private final transient List<JButton> rowOpenButtons = new ArrayList<>();

  private transient ExistClient client;
  private transient String serverId;
  private transient String cursor;
  private int totalItems;
  private int page = 1;
  private int pageSize = 10;
  private boolean indent = true;
  /** Whether to paint full-text hits (<exist:match>/<mark>) in results; session-only, default on. */
  private boolean highlightMatches = true;
  /** When true, long result lines wrap to the viewport width instead of scrolling horizontally. */
  private boolean wrap;
  private int selectedIndex;
  private int currentStart;
  /** Suppresses combo action-listener refreshes while {@link #applyPreferences()} sets values. */
  private transient boolean applyingPrefs;
  /** Monospaced point size of the result text, adjusted by the Cmd +/-/0 zoom shortcuts. */
  private int fontSize = DEFAULT_FONT_SIZE;
  /** Last viewport width the rows were wrapped for; guards re-wrap on scroll vs. genuine resize. */
  private int lastWrapWidth = -1;

  public ExistResultsView(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    super(new BorderLayout());
    this.workspace = workspace;
    this.profileStore = profileStore;
    // Start from the saved result-display preferences (persist across restarts).
    this.indent = profileStore.resultsIndent();
    this.wrap = profileStore.resultsWrap();
    this.pageSize = profileStore.resultsPageSize();
    final boolean initialIndent = this.indent;
    final boolean initialWrap = this.wrap;

    indentButton = OxygenUIComponentsFactory.createToolbarToggleButton(new AbstractAction() {
      {
        putValue(SMALL_ICON, icon("/images/PrettyPrint16.png"));
        putValue(NAME, "Indent");
        putValue(SHORT_DESCRIPTION, "Indent (pretty-print) results");
        putValue(SELECTED_KEY, initialIndent);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        indent = Boolean.TRUE.equals(getValue(SELECTED_KEY));
        refreshPage();
      }
    }, false);

    highlightButton = OxygenUIComponentsFactory.createToolbarToggleButton(new AbstractAction() {
      {
        putValue(SMALL_ICON, icon("/images/ContentHighlightActivated16.png"));
        putValue(NAME, "Highlight");
        putValue(SHORT_DESCRIPTION, "Highlight full-text hits (<exist:match>/<mark>) in results");
        putValue(SELECTED_KEY, highlightMatches);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        highlightMatches = Boolean.TRUE.equals(getValue(SELECTED_KEY));
        refreshPage();
      }
    }, false);

    wrapButton = OxygenUIComponentsFactory.createToolbarToggleButton(new AbstractAction() {
      {
        putValue(SMALL_ICON, WrapIcon.INSTANCE);
        putValue(NAME, "Wrap");
        putValue(SHORT_DESCRIPTION, "Wrap long result lines to the view width");
        putValue(SELECTED_KEY, initialWrap);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        wrap = Boolean.TRUE.equals(getValue(SELECTED_KEY));
        refreshPage();
      }
    }, false);

    firstButton = navButton(ChevronIcon.DOUBLE_LEFT, "First page", () -> goToPage(1));
    prevButton = navButton(ChevronIcon.LEFT, "Previous page", () -> goToPage(page - 1));
    prevItemButton = navButton(ChevronIcon.UP, "Previous result", () -> stepItem(-1));
    nextItemButton = navButton(ChevronIcon.DOWN, "Next result", () -> stepItem(1));
    nextButton = navButton(ChevronIcon.RIGHT, "Next page", () -> goToPage(page + 1));
    lastButton = navButton(ChevronIcon.DOUBLE_RIGHT, "Last page", () -> goToPage(pageCount()));

    constrainWidth(methodCombo, 120);
    constrainWidth(pageSizeCombo, 80);
    methodCombo.setSelectedIndex(methodIndex(profileStore.resultsMethod()));
    methodCombo.addActionListener(e -> {
      if (!applyingPrefs) {
        refreshPage();
      }
    });
    pageSizeCombo.setSelectedItem(pageSize);
    pageSizeCombo.addActionListener(e -> {
      if (applyingPrefs) {
        return;
      }
      pageSize = (Integer) pageSizeCombo.getSelectedItem();
      goToPage(1);
    });

    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    rows.setBackground(Color.WHITE);
    // Paint the area below the last row white too (the rows panel only covers its own height).
    scrollPane.getViewport().setBackground(Color.WHITE);

    layered.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
    layered.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        scrollPane.setBounds(0, 0, layered.getWidth(), layered.getHeight());
        applyWrapWidths();
        positionFloatingCopies();
      }
    });
    // Keep the per-row copy buttons glued to the viewport's right edge as the user scrolls; re-wrap
    // only when the viewport width actually changes (e.g. a vertical scrollbar appears), not on scroll.
    scrollPane.getViewport().addChangeListener(e -> {
      positionFloatingCopies();
      maybeRewrap();
    });

    add(buildToolbar(), BorderLayout.NORTH);
    add(layered, BorderLayout.CENTER);
    add(buildMetricsBar(), BorderLayout.SOUTH);
    updateNavState();

    // Re-apply the defaults live when they're edited in "Configure eXist-db Connections".
    profileStore.addResultsPrefsListener(this::applyPreferences);

    installZoomShortcuts();
  }

  /**
   * Binds Cmd/Ctrl +/-/0 to zoom the result text in/out/reset, matching Oxygen's editor zoom. Bound
   * on the ancestor-of-focused-component map so it fires whenever focus is anywhere inside this view
   * (a result pane, a toolbar combo, …) without shadowing the editor's own zoom.
   */
  private void installZoomShortcuts() {
    int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    InputMap in = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    ActionMap am = getActionMap();
    bindZoom(in, am, "existZoomIn", () -> zoomBy(2),
        KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuMask),
        KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menuMask),
        KeyStroke.getKeyStroke(KeyEvent.VK_ADD, menuMask));
    bindZoom(in, am, "existZoomOut", () -> zoomBy(-2),
        KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuMask),
        KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, menuMask));
    bindZoom(in, am, "existZoomReset", this::resetZoom,
        KeyStroke.getKeyStroke(KeyEvent.VK_0, menuMask),
        KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0, menuMask));
  }

  private static void bindZoom(InputMap in, ActionMap am, String key, Runnable action,
      KeyStroke... strokes) {
    for (KeyStroke stroke : strokes) {
      in.put(stroke, key);
    }
    am.put(key, new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        action.run();
      }
    });
  }

  private void zoomBy(int delta) {
    int next = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize + delta));
    if (next != fontSize) {
      fontSize = next;
      applyFontSize();
    }
  }

  private void resetZoom() {
    if (fontSize != DEFAULT_FONT_SIZE) {
      fontSize = DEFAULT_FONT_SIZE;
      applyFontSize();
    }
  }

  /** Restyles the visible result panes at the current {@link #fontSize} and relays the rows. */
  private void applyFontSize() {
    Font font = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
    for (JTextPane pane : rowTextPanes) {
      pane.setFont(font);
    }
    applyWrapWidths();
    SwingUtilities.invokeLater(this::positionFloatingCopies);
  }

  /**
   * Re-reads the saved result-display defaults (serialization method, indent, page size) into this
   * view's controls and re-renders, so edits in the connections dialog take effect immediately
   * instead of only on the next restart.
   */
  public void applyPreferences() {
    applyingPrefs = true;
    try {
      indent = profileStore.resultsIndent();
      indentButton.getAction().putValue(Action.SELECTED_KEY, indent);
      wrap = profileStore.resultsWrap();
      wrapButton.getAction().putValue(Action.SELECTED_KEY, wrap);
      methodCombo.setSelectedIndex(methodIndex(profileStore.resultsMethod()));
      pageSize = profileStore.resultsPageSize();
      pageSizeCombo.setSelectedItem(pageSize);
    } finally {
      applyingPrefs = false;
    }
    goToPage(1);
  }

  /** A small copy button that floats over the right edge of the viewport for one result row. */
  private JButton buildCopyButton(int rowIndex) {
    JButton button = OxygenUIComponentsFactory.createToolbarButton(new AbstractAction() {
      {
        ImageIcon ic = icon("/images/Copy16.png");
        if (ic != null) {
          putValue(SMALL_ICON, ic);
        } else {
          putValue(NAME, "Copy");
        }
        putValue(SHORT_DESCRIPTION, "Copy this result to the clipboard");
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        copyResult(rowIndex);
      }
    }, false);
    button.setOpaque(true);
    button.setBackground(Color.WHITE);
    button.setBorder(BorderFactory.createLineBorder(new Color(0xC8, 0xCE, 0xD6)));
    button.setVisible(false);
    return button;
  }

  private void copyResult(int rowIndex) {
    if (rowIndex >= 0 && rowIndex < pageItems.size()) {
      Toolkit.getDefaultToolkit().getSystemClipboard()
          .setContents(new StringSelection(pageItems.get(rowIndex).value()), null);
      workspace.showStatusMessage("Copied result " + (currentStart + rowIndex));
    }
  }

  /** A small "open source" button that floats next to a row's copy button for stored-node results. */
  private JButton buildOpenButton(int rowIndex) {
    JButton button = OxygenUIComponentsFactory.createToolbarButton(new AbstractAction() {
      {
        putValue(SMALL_ICON, ExternalLinkIcon.INSTANCE);
        putValue(SHORT_DESCRIPTION, "Open the source document and select this result");
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        openSource(rowIndex);
      }
    }, false);
    button.setOpaque(true);
    button.setBackground(Color.WHITE);
    button.setBorder(BorderFactory.createLineBorder(new Color(0xC8, 0xCE, 0xD6)));
    button.setVisible(false);
    return button;
  }

  private void openSource(int rowIndex) {
    if (rowIndex >= 0 && rowIndex < pageItems.size()) {
      QueryRunner.Item item = pageItems.get(rowIndex);
      if (item.hasSource()) {
        SourceNodeOpener.open(workspace, serverId, item.documentURI(), item.nodeId());
      }
    }
  }

  /**
   * Places each row's floating controls at the viewport's right edge, level with its row (or hides
   * them when the row is scrolled out of view). The copy button sits at the right edge; the
   * open-source button, when the result maps to a stored node, sits just to its left.
   */
  private void positionFloatingCopies() {
    JViewport viewport = scrollPane.getViewport();
    Rectangle visible = viewport.getViewRect();
    Point viewportTopLeft = SwingUtilities.convertPoint(viewport, 0, 0, layered);
    for (int i = 0; i < rowCopyButtons.size() && i < rowPanels.size(); i++) {
      JButton copyButton = rowCopyButtons.get(i);
      JButton openButton = i < rowOpenButtons.size() ? rowOpenButtons.get(i) : null;
      Rectangle rowBounds = rowPanels.get(i).getBounds();
      if (!visible.intersects(rowBounds)) {
        copyButton.setVisible(false);
        if (openButton != null) {
          openButton.setVisible(false);
        }
        continue;
      }
      int width = copyButton.getPreferredSize().width;
      int height = copyButton.getPreferredSize().height;
      int x = viewportTopLeft.x + viewport.getWidth() - width - 6;
      int y = viewportTopLeft.y + Math.max(0, rowBounds.y - visible.y) + 4;
      copyButton.setBounds(x, y, width, height);
      copyButton.setVisible(true);
      if (openButton != null) {
        if (i < pageItems.size() && pageItems.get(i).hasSource()) {
          int openWidth = openButton.getPreferredSize().width;
          openButton.setBounds(x - openWidth - 6, y, openWidth, height);
          openButton.setVisible(true);
        } else {
          openButton.setVisible(false);
        }
      }
    }
  }

  private JComponent buildToolbar() {
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    left.add(methodCombo);
    left.add(wrapButton);
    left.add(indentButton);
    left.add(highlightButton);

    JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
    nav.add(firstButton);
    nav.add(prevButton);
    nav.add(prevItemButton);
    nav.add(rangeLabel);
    nav.add(nextItemButton);
    nav.add(nextButton);
    nav.add(lastButton);

    JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
    right.add(pageSizeCombo);

    // Equal-weight side cells alone don't center the nav: GridBag only splits the *extra* space
    // equally, so unequal preferred widths still bias it. Force both sides to the same preferred
    // width (the larger one) so the navigation group is truly window-centered.
    int side = Math.max(left.getPreferredSize().width, right.getPreferredSize().width);
    left.setPreferredSize(new Dimension(side, left.getPreferredSize().height));
    right.setPreferredSize(new Dimension(side, right.getPreferredSize().height));

    JPanel bar = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridy = 0;
    c.gridx = 0;
    c.weightx = 1.0;
    bar.add(left, c);
    c.gridx = 1;
    c.weightx = 0;
    bar.add(nav, c);
    c.gridx = 2;
    c.weightx = 1.0;
    bar.add(right, c);
    return bar;
  }

  private static int methodIndex(String method) {
    for (int i = 0; i < METHOD_VALUES.length; i++) {
      if (METHOD_VALUES[i].equals(method)) {
        return i;
      }
    }
    return 0;
  }

  private static void constrainWidth(JComponent component, int width) {
    Dimension size = new Dimension(width, component.getPreferredSize().height);
    component.setPreferredSize(size);
    component.setMaximumSize(size);
  }

  private JComponent buildMetricsBar() {
    JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    south.add(metricsLabel);
    return south;
  }

  private JButton navButton(Icon icon, String tooltip, Runnable action) {
    return OxygenUIComponentsFactory.createToolbarButton(new AbstractAction() {
      {
        putValue(SMALL_ICON, icon);
        putValue(SHORT_DESCRIPTION, tooltip);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        action.run();
      }
    }, false);
  }

  /** Runs {@code query} on {@code client}, opening a fresh cursor and showing the first page. */
  public void run(ExistClient existClient, String serverIdentifier, String query,
      String moduleLoadPath, String contextItem) {
    closeCursorQuietly();
    this.client = existClient;
    this.serverId = serverIdentifier;
    metricsLabel.setText("Running…");
    new SwingWorker<ExistClient.QueryHandle, Void>() {
      @Override
      protected ExistClient.QueryHandle doInBackground() throws Exception {
        return existClient.runQuery(query, moduleLoadPath, contextItem);
      }

      @Override
      protected void done() {
        try {
          ExistClient.QueryHandle handle = get();
          cursor = handle.cursor();
          totalItems = handle.items();
          metricsLabel.setText("Compile: " + handle.compileMs() + " ms Eval: "
              + handle.evalMs() + " ms Total: " + handle.totalMs() + " ms Items: "
              + totalItems);
          selectedIndex = totalItems > 0 ? 1 : 0;
          goToPage(1);
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          showMessage("Query failed: " + cause.getMessage());
          metricsLabel.setText(" ");
        }
      }
    }.execute();
  }

  private void goToPage(int target) {
    int count = pageCount();
    page = Math.max(1, Math.min(target, Math.max(1, count)));
    refreshPage();
  }

  private void refreshPage() {
    if (cursor == null || totalItems == 0) {
      rows.removeAll();
      rowPanels.clear();
      rowTextPanes.clear();
      for (JButton button : rowCopyButtons) {
        layered.remove(button);
      }
      rowCopyButtons.clear();
      for (JButton button : rowOpenButtons) {
        layered.remove(button);
      }
      rowOpenButtons.clear();
      layered.repaint();
      rangeLabel.setText("No results");
      rows.revalidate();
      rows.repaint();
      updateNavState();
      return;
    }
    final int start = (page - 1) * pageSize + 1;
    final int count = Math.min(pageSize, totalItems - start + 1);
    final String method = METHOD_VALUES[Math.max(0, methodCombo.getSelectedIndex())];
    final boolean doIndent = indent;
    final ExistClient activeClient = client;
    final String activeCursor = cursor;
    new SwingWorker<List<QueryRunner.Item>, Void>() {
      @Override
      protected List<QueryRunner.Item> doInBackground() throws Exception {
        String body = activeClient.fetchResultsRaw(activeCursor, start, count, method, doIndent);
        JSONArray array = new JSONArray(body);
        List<QueryRunner.Item> items = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
          JSONObject item = array.getJSONObject(i);
          items.add(new QueryRunner.Item(item.optString("value", item.toString()),
              item.optString("type", ""), item.optString("documentURI", ""),
              item.optString("nodeId", "")));
        }
        return items;
      }

      @Override
      protected void done() {
        try {
          renderRows(get(), start, method);
          rangeLabel.setText(
              "Showing results " + start + " to " + (start + count - 1) + " of " + totalItems);
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          showMessage("Could not fetch results: " + cause.getMessage());
        }
        updateNavState();
      }
    }.execute();
  }

  private void renderRows(List<QueryRunner.Item> items, int startIndex, String method) {
    currentStart = startIndex;
    rows.removeAll();
    rowPanels.clear();
    rowTextPanes.clear();
    pageItems.clear();
    pageItems.addAll(items);
    // Drop the previous page's floating buttons before laying out the new ones.
    for (JButton button : rowCopyButtons) {
      layered.remove(button);
    }
    rowCopyButtons.clear();
    for (JButton button : rowOpenButtons) {
      layered.remove(button);
    }
    rowOpenButtons.clear();
    for (int i = 0; i < items.size(); i++) {
      JPanel row = buildRow(startIndex + i, items.get(i), method);
      rowPanels.add(row);
      rows.add(row);
      JButton copyButton = buildCopyButton(i);
      rowCopyButtons.add(copyButton);
      layered.add(copyButton, JLayeredPane.PALETTE_LAYER);
      JButton openButton = buildOpenButton(i);
      rowOpenButtons.add(openButton);
      layered.add(openButton, JLayeredPane.PALETTE_LAYER);
    }
    applyWrapWidths();
    applyHighlight();
  }

  /**
   * Sizes each result row for the current wrap mode. When wrapping is on, every row's text pane is
   * bounded to the viewport width (minus the number cell and insets) and measured for its wrapped
   * height, so long lines wrap instead of scrolling horizontally; when off, panes keep their natural
   * (longest-line) width and the view scrolls horizontally as before.
   */
  private void applyWrapWidths() {
    int viewportWidth = scrollPane.getViewport().getWidth();
    boolean doWrap = wrap && viewportWidth > 0;
    for (int i = 0; i < rowPanels.size() && i < rowTextPanes.size(); i++) {
      JPanel row = rowPanels.get(i);
      JTextPane pane = rowTextPanes.get(i);
      pane.setPreferredSize(null);
      if (doWrap) {
        int paneWidth = Math.max(MIN_WRAP_WIDTH, viewportWidth - WRAP_CHROME);
        pane.setSize(paneWidth, Short.MAX_VALUE);
        pane.setPreferredSize(new Dimension(paneWidth, pane.getPreferredSize().height));
        row.setMaximumSize(new Dimension(viewportWidth, row.getPreferredSize().height));
      } else {
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
      }
    }
    lastWrapWidth = viewportWidth;
    rows.revalidate();
    rows.repaint();
  }

  /** Re-wraps the rows only when the viewport width has changed since the last wrap. */
  private void maybeRewrap() {
    if (wrap && scrollPane.getViewport().getWidth() != lastWrapWidth) {
      applyWrapWidths();
    }
  }

  /** Steps the highlighted result by {@code delta}, crossing page boundaries as needed. */
  private void stepItem(int delta) {
    if (totalItems == 0) {
      return;
    }
    int target = Math.max(1, Math.min((selectedIndex <= 0 ? 1 : selectedIndex + delta), totalItems));
    selectedIndex = target;
    int neededPage = (target - 1) / pageSize + 1;
    if (neededPage != page) {
      goToPage(neededPage); // refreshPage → renderRows → applyHighlight
    } else {
      applyHighlight();
    }
  }

  /** Paints the selected row's highlight (others striped/plain) and scrolls it into view. */
  private void applyHighlight() {
    JPanel selectedRow = null;
    for (int i = 0; i < rowPanels.size(); i++) {
      JPanel row = rowPanels.get(i);
      boolean selected = currentStart + i == selectedIndex;
      row.setBackground(selected ? SELECTION : (i % 2 == 1 ? STRIPE : PLAIN));
      if (selected) {
        selectedRow = row;
      }
    }
    final JPanel target = selectedRow;
    SwingUtilities.invokeLater(() -> {
      if (target != null) {
        // getBounds() is in the rows panel's coordinate space, so scroll on the parent.
        rows.scrollRectToVisible(target.getBounds());
      }
      positionFloatingCopies();
    });
    updateNavState();
  }

  private JPanel buildRow(int number, QueryRunner.Item item, String method) {
    String value = item.value();
    JPanel row = new JPanel(new BorderLayout(8, 0));
    row.setBorder(BorderFactory.createEmptyBorder(7, 6, 7, 6));
    row.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        selectedIndex = number;
        applyHighlight();
      }
    });

    row.add(buildNumberLabel(number), BorderLayout.WEST);

    JTextPane area = new JTextPane() {
      @Override
      public boolean getScrollableTracksViewportWidth() {
        return false; // no wrapping — long values get a horizontal scrollbar
      }
    };
    area.setEditable(false);
    area.setOpaque(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
    ResultHighlighter.apply(area.getStyledDocument(), value,
        ResultHighlighter.languageFor(method, value), highlightMatches);
    rowTextPanes.add(area);
    // Per-result copy is provided by the floating button at the viewport's right edge, so it stays
    // visible even when a result is wider than the view.
    row.add(area, BorderLayout.CENTER);
    // Cap the row's height to its content so BoxLayout doesn't stretch a lone result to fill (and
    // shade) the whole viewport; width is free so wide results can scroll horizontally.
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
    row.setAlignmentX(LEFT_ALIGNMENT);
    return row;
  }

  private void updateNavState() {
    int count = pageCount();
    firstButton.setEnabled(page > 1);
    prevButton.setEnabled(page > 1);
    nextButton.setEnabled(page < count);
    lastButton.setEnabled(page < count);
    prevItemButton.setEnabled(selectedIndex > 1);
    nextItemButton.setEnabled(selectedIndex > 0 && selectedIndex < totalItems);
  }

  private int pageCount() {
    return totalItems == 0 ? 1 : (totalItems + pageSize - 1) / pageSize;
  }

  private void closeCursorQuietly() {
    if (client != null && cursor != null) {
      String old = cursor;
      ExistClient owner = client;
      cursor = null;
      new SwingWorker<Void, Void>() {
        @Override
        protected Void doInBackground() {
          try {
            owner.closeCursor(old);
          } catch (Exception ignored) {
            // best-effort release
          }
          return null;
        }
      }.execute();
    }
  }

  private void showMessage(String message) {
    workspace.showErrorMessage(message);
  }

  private static ImageIcon icon(String resource) {
    URL url = ExistResultsView.class.getResource(resource);
    return url != null ? new ImageIcon(url) : null;
  }

  @Override
  public Dimension getPreferredSize() {
    Dimension d = super.getPreferredSize();
    return new Dimension(Math.max(d.width, 400), Math.max(d.height, 240));
  }

  /**
   * The row's number cell — a plain grey number. Opening the source document is offered by the
   * floating open-source button next to the row's copy button (see {@link #buildOpenButton(int)}).
   */
  private JLabel buildNumberLabel(int number) {
    JLabel num = new JLabel(String.valueOf(number), SwingConstants.CENTER);
    num.setPreferredSize(new Dimension(44, 1));
    num.setVerticalAlignment(SwingConstants.TOP);
    num.setForeground(Color.GRAY);
    return num;
  }

  /**
   * A vector "word-wrap" glyph — a line that runs right, hooks down, and returns left with an
   * arrowhead — for the wrap toggle (the Oxygen SDK ships no wrap icon). Drawn like
   * {@link ChevronIcon} so it stays crisp at any zoom level.
   */
  private static final class WrapIcon implements Icon {
    static final Icon INSTANCE = new WrapIcon();
    private static final int SIZE = 16;

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(c.isEnabled() ? new Color(0x55, 0x5F, 0x6D) : new Color(0xB8, 0xBE, 0xC6));
      g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      // Top full-width line.
      g2.drawLine(x + 3, y + 4, x + 13, y + 4);
      // Second line that wraps: run right, hook down, and return left.
      g2.drawPolyline(
          new int[] {x + 3, x + 12, x + 12, x + 6},
          new int[] {y + 8, y + 8, y + 11, y + 11}, 4);
      // Arrowhead at the returned (left) end, pointing left.
      g2.drawPolyline(new int[] {x + 8, x + 6, x + 8}, new int[] {y + 9, y + 11, y + 13}, 3);
      g2.dispose();
    }
  }

  /**
   * A vector "external link" icon — a box with an arrow leaving its top-right corner — for the
   * open-source button, drawn (like {@link ChevronIcon}) so it stays crisp at any zoom level.
   */
  private static final class ExternalLinkIcon implements Icon {
    static final Icon INSTANCE = new ExternalLinkIcon();
    private static final int SIZE = 16;

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(c.isEnabled() ? new Color(0x55, 0x5F, 0x6D) : new Color(0xB8, 0xBE, 0xC6));
      g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      // The box, drawn with its top-right corner left open so the arrow can exit through it.
      g2.drawPolyline(
          new int[] {x + 8, x + 3, x + 3, x + 12, x + 12},
          new int[] {y + 4, y + 4, y + 13, y + 13, y + 8}, 5);
      // The arrow: a shaft from inside the box to the top-right, plus its head.
      g2.drawLine(x + 7, y + 9, x + 13, y + 3);
      g2.drawPolyline(new int[] {x + 9, x + 13, x + 13}, new int[] {y + 3, y + 3, y + 7}, 3);
      g2.dispose();
    }
  }

  /** A uniform, vector chevron icon for the navigation buttons (so widths match, unlike glyphs). */
  private static final class ChevronIcon implements Icon {
    private enum Kind { LEFT, RIGHT, UP, DOWN, DOUBLE_LEFT, DOUBLE_RIGHT }

    static final Icon LEFT = new ChevronIcon(Kind.LEFT);
    static final Icon RIGHT = new ChevronIcon(Kind.RIGHT);
    static final Icon UP = new ChevronIcon(Kind.UP);
    static final Icon DOWN = new ChevronIcon(Kind.DOWN);
    static final Icon DOUBLE_LEFT = new ChevronIcon(Kind.DOUBLE_LEFT);
    static final Icon DOUBLE_RIGHT = new ChevronIcon(Kind.DOUBLE_RIGHT);

    private static final int SIZE = 16;
    private final transient Kind kind;

    private ChevronIcon(Kind kind) {
      this.kind = kind;
    }

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(c.isEnabled() ? new Color(0x55, 0x5F, 0x6D) : new Color(0xB8, 0xBE, 0xC6));
      g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      switch (kind) {
        case LEFT -> chevron(g2, x + 10, y + 3, x + 6, y + 8, x + 10, y + 13);
        case RIGHT -> chevron(g2, x + 6, y + 3, x + 10, y + 8, x + 6, y + 13);
        case UP -> chevron(g2, x + 3, y + 10, x + 8, y + 6, x + 13, y + 10);
        case DOWN -> chevron(g2, x + 3, y + 6, x + 8, y + 10, x + 13, y + 6);
        case DOUBLE_LEFT -> {
          chevron(g2, x + 8, y + 3, x + 4, y + 8, x + 8, y + 13);
          chevron(g2, x + 12, y + 3, x + 8, y + 8, x + 12, y + 13);
        }
        case DOUBLE_RIGHT -> {
          chevron(g2, x + 4, y + 3, x + 8, y + 8, x + 4, y + 13);
          chevron(g2, x + 8, y + 3, x + 12, y + 8, x + 8, y + 13);
        }
        default -> { /* unreachable */ }
      }
      g2.dispose();
    }

    private static void chevron(Graphics2D g2, int x1, int y1, int x2, int y2, int x3, int y3) {
      g2.drawPolyline(new int[] {x1, x2, x3}, new int[] {y1, y2, y3}, 3);
    }
  }
}
