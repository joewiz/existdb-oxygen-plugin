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
import com.existdb.oxygen.client.ExistHttpException;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.workspace.api.options.WSOptionsStorage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
  /** Top-k for a vector "find similar" search (existdb-openapi#60 clamps k to 1–100). */
  private static final int VECTOR_K = 20;
  /** The scope the field picker discovers searchable fields under (the whole database). */
  private static final String FIELD_SCOPE = "/db";
  // Persisted across invocations so the dialog reopens with the last server/field/query (#2).
  private static final String OPT_SERVER = "existdb.search.lastServerId";
  private static final String OPT_FIELD = "existdb.search.lastField";
  private static final String OPT_QUERY = "existdb.search.lastQuery";
  private static final String INTRO_TEXT = "Search named fields on the selected eXist-db server. The "
      + "\"site-content\" field contains eXist-db documentation included in default eXist.";

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
  /** The remembered field name to re-select once discovery completes; applied once, then cleared. */
  private transient String pendingFieldName;
  /** The embedding model from the last vector search response, for the "Copy as XQuery" output. */
  private transient String lastVectorModel = "";
  /** Active facet filters ("dimension:value") narrowing the current search; drives the &facet params. */
  private final transient List<String> activeFacetFilters = new ArrayList<>();
  /** The facet drill-down panel (one group of checkboxes per dimension), right of the results. */
  private final JPanel facetBox = new JPanel();
  /** Scroll wrapper around {@link #facetBox}; shown only when a search returns facet buckets. */
  private transient JComponent facetScroll;

  private SearchDialog(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace,
      String preselectServerId) {
    super(owner, "Search eXist-db", false);
    this.workspace = workspace;
    this.profiles = store.loadAll();
    String[] names = profiles.stream().map(ConnectionProfile::getName).toArray(String[]::new);
    serverCombo = OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(names));
    // Server precedence: an explicit pre-selection (e.g. a server's right-click → Search) wins;
    // otherwise the last-used server; otherwise the configured default.
    String wantServer = preselectServerId != null && !preselectServerId.isBlank()
        ? preselectServerId : option(OPT_SERVER, "");
    if (!selectServerById(wantServer)) {
      selectServerById(store.defaultProfileId());
    }
    pendingFieldName = option(OPT_FIELD, "");
    buildUi();
    queryField.setText(option(OPT_QUERY, ""));
    setDefaultCloseOperation(DISPOSE_ON_CLOSE); // so closing via the window button also persists
    setSize(640, 420);
    setLocationRelativeTo(owner);
  }

  /** Opens a non-modal search window with the default/last-used server selected. */
  public static void open(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace) {
    open(owner, store, workspace, null);
  }

  /** Opens a non-modal search window with {@code preselectServerId} selected (server right-click). */
  public static void open(Frame owner, ProfileStore store, StandalonePluginWorkspace workspace,
      String preselectServerId) {
    SearchDialog dialog = new SearchDialog(owner, store, workspace, preselectServerId);
    dialog.setVisible(true);
    dialog.queryField.requestFocusInWindow();
  }

  private boolean selectServerById(String id) {
    if (id == null || id.isBlank()) {
      return false;
    }
    for (int i = 0; i < profiles.size(); i++) {
      if (id.equals(profiles.get(i).getId())) {
        serverCombo.setSelectedIndex(i);
        return true;
      }
    }
    return false;
  }

  private void selectFieldByName(String name) {
    if (name == null || name.isBlank()) {
      return;
    }
    for (int i = 0; i < fieldCombo.getItemCount(); i++) {
      ExistClient.SearchFieldInfo f = fieldCombo.getItemAt(i);
      if (f != null && name.equals(f.field())) {
        fieldCombo.setSelectedIndex(i);
        return;
      }
    }
  }

  private String option(String key, String def) {
    return workspace.getOptionsStorage().getOption(key, def);
  }

  /** Persists the current server/field/query so the next invocation restores them (#2). */
  private void rememberSelections() {
    WSOptionsStorage opts = workspace.getOptionsStorage();
    String serverId = selectedServerId();
    opts.setOption(OPT_SERVER, serverId == null ? "" : serverId);
    ExistClient.SearchFieldInfo field = (ExistClient.SearchFieldInfo) fieldCombo.getSelectedItem();
    opts.setOption(OPT_FIELD, field == null ? "" : field.field());
    opts.setOption(OPT_QUERY, queryField.getText());
  }

  @Override
  public void dispose() {
    rememberSelections();
    super.dispose();
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
    fieldCombo.setToolTipText("The fields/facets/vector indexes this server exposes (only those you "
        + "may see). Choose a field for keyword search, a vector index for \"find similar\" "
        + "(semantic) search, or \"All fields\" for the default keyword search.");
    fieldHint.setForeground(Color.GRAY);
    JPanel fieldRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    fieldRow.add(new JLabel("Search in:"));
    fieldRow.add(fieldCombo);
    fieldRow.add(fieldHint);
    serverCombo.addActionListener(e -> {
      // A different server has its own fields and facets — drop any active drill-down.
      activeFacetFilters.clear();
      rebuildFacetPanel(Map.of());
      fetchFields();
    });

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
    // "Copy as URL/XQuery" for the current query — left of the status, out of the way of Open.
    JPanel copyButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    copyButtons.add(OxygenUIComponentsFactory.createButton(new AbstractAction("Copy as URL") {
      @Override
      public void actionPerformed(ActionEvent e) {
        copyAsUrl();
      }
    }));
    copyButtons.add(OxygenUIComponentsFactory.createButton(new AbstractAction("Copy as XQuery") {
      @Override
      public void actionPerformed(ActionEvent e) {
        copyAsXQuery();
      }
    }));
    south.add(copyButtons, BorderLayout.WEST);
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

    // Results on the left, the facet drill-down panel on the right (hidden until a search returns
    // facet buckets). The facet box is a vertical stack of dimension groups.
    facetBox.setLayout(new BoxLayout(facetBox, BoxLayout.Y_AXIS));
    facetScroll = OxygenUIComponentsFactory.createScrollPane(facetBox,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    facetScroll.setPreferredSize(new Dimension(190, 0));
    facetScroll.setVisible(false);
    JComponent listScroll = OxygenUIComponentsFactory.createScrollPane(list,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    JPanel center = new JPanel(new BorderLayout(8, 0));
    center.add(listScroll, BorderLayout.CENTER);
    center.add(facetScroll, BorderLayout.EAST);

    setLayout(new BorderLayout(8, 8));
    ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    add(north, BorderLayout.NORTH);
    add(center, BorderLayout.CENTER);
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
    // The selected field (null = "All fields"). A "vector" kind switches to semantic "find similar"
    // (kNN) search; a "field" kind restricts the keyword query to it; "All fields" is the plain
    // sitewide keyword search.
    ExistClient.SearchFieldInfo field = (ExistClient.SearchFieldInfo) fieldCombo.getSelectedItem();
    boolean vector = field != null && "vector".equals(field.kind());
    if (vector) {
      activeFacetFilters.clear(); // similarity search returns no facets
    }
    rememberSelections(); // record what was actually searched, not just on close
    status.setText(vector ? "Finding similar…" : "Searching…");
    status.setToolTipText(null);
    model.clear();
    List<String> facetFilters = vector ? List.of() : List.copyOf(activeFacetFilters);
    new SwingWorker<ExistClient.SearchResults, Void>() {
      @Override
      protected ExistClient.SearchResults doInBackground() throws Exception {
        return runQuery(client, query, field, vector, facetFilters);
      }

      @Override
      protected void done() {
        try {
          ExistClient.SearchResults results = get();
          if (vector) {
            lastVectorModel = results.model(); // for the "Copy as XQuery" vector output
          }
          results.hits().forEach(model::addElement);
          status.setText(resultStatus(vector, results.hits().size(), results.total()));
          rebuildFacetPanel(results.facets());
          if (!model.isEmpty()) {
            // Move focus to the results and select the first hit, so Up/Down navigate immediately
            // and Enter (the default Open button) opens the selection.
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
            list.requestFocusInWindow();
          }
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          String message = failureMessage(cause, field);
          status.setText(message);
          status.setToolTipText(message); // CENTER clips a long error; keep the full text on hover.
        }
      }
    }.execute();
  }

  /** Runs the right query for the selection: vector similarity, field-scoped, or sitewide keyword. */
  private ExistClient.SearchResults runQuery(ExistClient client, String query,
      ExistClient.SearchFieldInfo field, boolean vector, List<String> facetFilters)
      throws IOException, InterruptedException {
    if (vector) {
      return client.searchVector(field.field(), query, VECTOR_K, FIELD_SCOPE);
    }
    return field == null
        ? client.search(query, null, null, facetFilters, LIMIT)
        : client.search(query, field.field(), FIELD_SCOPE, facetFilters, LIMIT);
  }

  /** The status line for a completed search: a similarity count, a paged count, or a match count. */
  private static String resultStatus(boolean vector, int shown, int total) {
    if (vector) {
      return shown + " similar " + (shown == 1 ? "document" : "documents");
    }
    return total > shown
        ? "Showing " + shown + " of " + total + " matches"
        : shown + " match" + (shown == 1 ? "" : "es");
  }

  /** A user-facing failure message; an FLS 403 on a chosen field gets a permission-specific note. */
  private static String failureMessage(Throwable cause, ExistClient.SearchFieldInfo field) {
    if (cause instanceof ExistHttpException he && he.getStatusCode() == 403) {
      return "You don't have permission to search the \""
          + (field != null ? field.field() : "selected") + "\" field on this server.";
    }
    return "Search failed: " + cause.getMessage();
  }

  /** Copies the exact {@code /api/search} URL for the current query to the clipboard. */
  private void copyAsUrl() {
    ExistClient client = ExistContext.clientById(selectedServerId());
    if (client == null) {
      workspace.showInformationMessage("Select a connected server first.");
      return;
    }
    String query = queryField.getText().trim();
    ExistClient.SearchFieldInfo field = (ExistClient.SearchFieldInfo) fieldCombo.getSelectedItem();
    String url;
    if (field != null && "vector".equals(field.kind())) {
      url = client.searchVectorUrl(field.field(), query, VECTOR_K, FIELD_SCOPE);
    } else if (field == null) {
      url = client.searchUrl(query, null, null, List.copyOf(activeFacetFilters), LIMIT);
    } else {
      url = client.searchUrl(query, field.field(), FIELD_SCOPE, List.copyOf(activeFacetFilters), LIMIT);
    }
    toClipboard(url, "Copied search URL to the clipboard.");
  }

  /** Copies an XQuery for the current query — exact for vectors, a representative query for keyword. */
  private void copyAsXQuery() {
    String query = queryField.getText().trim();
    ExistClient.SearchFieldInfo field = (ExistClient.SearchFieldInfo) fieldCombo.getSelectedItem();
    String xquery = field != null && "vector".equals(field.kind())
        ? vectorXQuery(field.field(), query)
        : keywordXQuery(field, query);
    toClipboard(xquery, "Copied XQuery to the clipboard.");
  }

  /** The eXist vector pipeline behind a "Similar to…" search (model from the last vector response). */
  private String vectorXQuery(String field, String text) {
    String model = lastVectorModel == null || lastVectorModel.isBlank()
        ? "MODEL — run a Similar-to search first to resolve the model" : lastVectorModel;
    return "(: vector \"Similar to…\" search :)\n"
        + "let $hits := collection('" + FIELD_SCOPE + "')/ft:query-field-vector('" + field
        + "', vector:embed('" + xqString(text) + "', '" + xqString(model) + "'), " + VECTOR_K + ")\n"
        + "for $h in $hits\n"
        + "order by ft:score($h) descending\n"
        + "return $h";
  }

  /** A representative eXist full-text query for a keyword/field search (approximates /api/search). */
  private String keywordXQuery(ExistClient.SearchFieldInfo field, String query) {
    String lucene = field == null ? xqString(query) : field.field() + ":(" + xqString(query) + ")";
    return "(: approximates /api/search — facet filters, scoring and KWIC differ :)\n"
        + "collection('" + FIELD_SCOPE + "')//*[ft:query(., '" + lucene + "')]";
  }

  /** Escapes a value for an XQuery single-quoted string literal (doubles each apostrophe). */
  private static String xqString(String s) {
    return s == null ? "" : s.replace("'", "''");
  }

  private void toClipboard(String text, String confirmation) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    status.setText(confirmation);
    status.setToolTipText(text); // the full copied text on hover
  }

  /**
   * Rebuilds the facet drill-down panel from a search response's buckets ({@code dimension → value →
   * count}). Dimensions are sorted by name; within each, values by count (descending) then name. A
   * checkbox reflects/toggles whether that {@code "dimension:value"} filter is active. The panel is
   * shown only when there are buckets or active filters, so a plain search keeps the full-width list.
   */
  private void rebuildFacetPanel(Map<String, Map<String, Integer>> facets) {
    if (facetScroll == null) {
      return;
    }
    facetBox.removeAll();
    if (!activeFacetFilters.isEmpty()) {
      JButton clear = OxygenUIComponentsFactory.createButton(new AbstractAction("Clear filters") {
        @Override
        public void actionPerformed(ActionEvent e) {
          clearFacets();
        }
      });
      clear.setAlignmentX(LEFT_ALIGNMENT);
      facetBox.add(clear);
    }
    boolean hasFacets = facets != null && !facets.isEmpty();
    if (hasFacets) {
      facets.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(dim -> addFacetGroup(dim.getKey(), dim.getValue()));
    }
    facetScroll.setVisible(hasFacets || !activeFacetFilters.isEmpty());
    facetBox.revalidate();
    facetBox.repaint();
    getContentPane().revalidate();
  }

  /** Adds one dimension's group: a bold header plus a checkbox per value (count). */
  private void addFacetGroup(String dimension, Map<String, Integer> buckets) {
    JLabel header = new JLabel(dimension);
    header.setFont(header.getFont().deriveFont(Font.BOLD));
    header.setAlignmentX(LEFT_ALIGNMENT);
    header.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
    facetBox.add(header);
    buckets.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey()))
        .forEach(b -> {
          String key = dimension + ":" + b.getKey();
          JCheckBox box = new JCheckBox(b.getKey() + " (" + b.getValue() + ")",
              activeFacetFilters.contains(key));
          box.setAlignmentX(LEFT_ALIGNMENT);
          box.addActionListener(e -> toggleFacet(key, box.isSelected()));
          facetBox.add(box);
        });
  }

  /** Adds/removes a {@code "dimension:value"} filter and re-runs the search to apply it. */
  private void toggleFacet(String key, boolean on) {
    if (on) {
      if (!activeFacetFilters.contains(key)) {
        activeFacetFilters.add(key);
      }
    } else {
      activeFacetFilters.remove(key);
    }
    doSearch();
  }

  /** Drops all active facet filters and re-runs the search. */
  private void clearFacets() {
    activeFacetFilters.clear();
    doSearch();
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
          // Restore the remembered field on the first discovery only; later server switches reset it.
          if (pendingFieldName != null && !pendingFieldName.isBlank()) {
            selectFieldByName(pendingFieldName);
          }
          pendingFieldName = null;
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
   * Renders a snippet as HTML. The server returns a KWIC fragment: each hit wrapped in {@code <mark>}
   * inside a {@code <span>} envelope (e.g. {@code <span>… <mark>tuning</mark> …</span>}). Strip every
   * tag except the {@code <mark>}s — otherwise the envelope renders as literal {@code <span>…</span>}
   * text — then collapse/truncate, escape, and turn the marks into a highlighted span (black on
   * yellow, so it stays readable on selected rows too). Snippets without {@code <mark>} render as
   * plain text.
   */
  private static String highlightSnippet(String raw) {
    String marksOnly = (raw == null ? "" : raw).replaceAll("(?i)<(?!/?mark\\b)[^>]*>", "");
    return escape(snippet(marksOnly))
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
