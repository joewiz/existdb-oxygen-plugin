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
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
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
  // Solid (non-alpha) colors: translucent backgrounds on opaque rows leave paint artifacts.
  private static final Color STRIPE = new Color(0xF2, 0xF4, 0xF7);
  private static final Color PLAIN = Color.WHITE;
  private static final Color SELECTION = new Color(0xD6, 0xE6, 0xFB);

  private final transient StandalonePluginWorkspace workspace;

  private final JComboBox<String> methodCombo =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(METHOD_LABELS));
  private final JComboBox<Integer> pageSizeCombo =
      OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(PAGE_SIZES));
  private final JButton indentButton;
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

  private transient ExistClient client;
  private transient String cursor;
  private int totalItems;
  private int page = 1;
  private int pageSize = 10;
  private boolean indent = true;
  private int selectedIndex;
  private int currentStart;

  public ExistResultsView(StandalonePluginWorkspace workspace) {
    super(new BorderLayout());
    this.workspace = workspace;

    indentButton = OxygenUIComponentsFactory.createToolbarToggleButton(new AbstractAction() {
      {
        putValue(SMALL_ICON, icon("/images/PrettyPrint16.png"));
        putValue(NAME, "Indent");
        putValue(SHORT_DESCRIPTION, "Indent (pretty-print) results");
        putValue(SELECTED_KEY, Boolean.TRUE);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        indent = Boolean.TRUE.equals(getValue(SELECTED_KEY));
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
    constrainWidth(pageSizeCombo, 64);
    methodCombo.addActionListener(e -> refreshPage());
    pageSizeCombo.setSelectedItem(pageSize);
    pageSizeCombo.addActionListener(e -> {
      pageSize = (Integer) pageSizeCombo.getSelectedItem();
      goToPage(1);
    });

    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    rows.setBackground(Color.WHITE);

    add(buildToolbar(), BorderLayout.NORTH);
    add(OxygenUIComponentsFactory.createScrollPane(rows,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
    add(buildMetricsBar(), BorderLayout.SOUTH);
    updateNavState();
  }

  private JComponent buildToolbar() {
    JToolBar bar = new JToolBar();
    bar.setFloatable(false);
    bar.setRollover(true);
    // Left: serialization controls. Center (glue both sides): page/result navigation. Right: page size.
    bar.add(methodCombo);
    bar.add(indentButton);
    bar.add(Box.createHorizontalGlue());
    bar.add(firstButton);
    bar.add(prevButton);
    bar.add(prevItemButton);
    bar.add(rangeLabel);
    bar.add(nextItemButton);
    bar.add(nextButton);
    bar.add(lastButton);
    bar.add(Box.createHorizontalGlue());
    bar.add(pageSizeCombo);
    return bar;
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
  public void run(ExistClient existClient, String query, String moduleLoadPath, String contextItem) {
    closeCursorQuietly();
    this.client = existClient;
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
    new SwingWorker<List<String>, Void>() {
      @Override
      protected List<String> doInBackground() throws Exception {
        String body = activeClient.fetchResultsRaw(activeCursor, start, count, method, doIndent);
        JSONArray array = new JSONArray(body);
        List<String> values = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
          JSONObject item = array.getJSONObject(i);
          values.add(item.optString("value", item.toString()));
        }
        return values;
      }

      @Override
      protected void done() {
        try {
          renderRows(get(), start);
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

  private void renderRows(List<String> values, int startIndex) {
    currentStart = startIndex;
    rows.removeAll();
    rowPanels.clear();
    for (int i = 0; i < values.size(); i++) {
      JPanel row = buildRow(startIndex + i, values.get(i));
      rowPanels.add(row);
      rows.add(row);
    }
    rows.revalidate();
    rows.repaint();
    applyHighlight();
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
    if (target != null) {
      // getBounds() is in the rows panel's coordinate space, so scroll on the parent.
      SwingUtilities.invokeLater(() -> rows.scrollRectToVisible(target.getBounds()));
    }
    updateNavState();
  }

  private JPanel buildRow(int number, String value) {
    JPanel row = new JPanel(new BorderLayout(8, 0));
    row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    row.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        selectedIndex = number;
        applyHighlight();
      }
    });

    JLabel num = new JLabel(String.valueOf(number), SwingConstants.CENTER);
    num.setPreferredSize(new Dimension(44, 1));
    num.setVerticalAlignment(SwingConstants.TOP);
    num.setForeground(Color.GRAY);
    row.add(num, BorderLayout.WEST);

    JTextArea area = new JTextArea(value);
    area.setEditable(false);
    area.setOpaque(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    area.setLineWrap(false);
    row.add(area, BorderLayout.CENTER);

    JButton copy = OxygenUIComponentsFactory.createToolbarButton(new AbstractAction() {
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
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(value), null);
        workspace.showStatusMessage("Copied result " + number);
      }
    }, false);
    JPanel copyHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    copyHolder.setOpaque(false);
    copyHolder.add(copy);
    row.add(copyHolder, BorderLayout.EAST);
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
