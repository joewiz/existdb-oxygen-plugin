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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * The eXist-db side view: a connection bar plus a lazily-loaded collection tree. Collections load
 * their children on first expand; double-clicking (or "Open") a resource opens it in an editor via
 * the {@code exist:} URL scheme so saving writes straight back to the database.
 */
public final class ExistdbBrowserPanel extends JPanel {

  private static final String DB_ROOT = "/db";

  private final StandalonePluginWorkspace workspace;
  private final ProfileStore profileStore;

  private final JLabel connectionLabel = new JLabel();
  private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
  private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
  private final JTree tree = new JTree(treeModel);

  public ExistdbBrowserPanel(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    super(new BorderLayout());
    this.workspace = workspace;
    this.profileStore = profileStore;

    // Keep the docked view from demanding a huge minimum width: the connection label below shows
    // only the profile name (full URL is a tooltip), and the panel reports a modest min/pref width.
    setMinimumSize(new Dimension(150, 0));
    setPreferredSize(new Dimension(260, 400));

    add(buildConnectionBar(), BorderLayout.NORTH);
    add(new JScrollPane(tree), BorderLayout.CENTER);
    add(buildButtonBar(), BorderLayout.SOUTH);

    configureTree();

    // Restore the saved profile and activate it (without forcing a network call yet).
    ConnectionProfile saved = profileStore.load();
    ExistContext.setActiveProfile(saved);
    updateConnectionLabel(saved);
  }

  private JPanel buildConnectionBar() {
    JButton connect = new JButton("Connect…");
    connect.addActionListener(e -> editConnection());
    // Let the label shrink (it ellipsizes) instead of dictating the pane's minimum width.
    connectionLabel.setMinimumSize(new Dimension(0, 0));
    JPanel bar = new JPanel(new BorderLayout(4, 0));
    bar.add(connectionLabel, BorderLayout.CENTER);
    bar.add(connect, BorderLayout.EAST);
    return bar;
  }

  private JPanel buildButtonBar() {
    JButton refresh = new JButton("Refresh");
    refresh.addActionListener(e -> reloadRoot());
    JButton open = new JButton("Open");
    open.addActionListener(e -> openSelected());
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
    bar.add(refresh);
    bar.add(open);
    return bar;
  }

  private void configureTree() {
    tree.setRootVisible(true);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.setCellRenderer(new ExistTreeCellRenderer());
    ToolTipManager.sharedInstance().registerComponent(tree);
    rootNode.setUserObject(new ExistNode(DB_ROOT, "db", true));
    addPlaceholder(rootNode);

    tree.addTreeWillExpandListener(new TreeWillExpandListener() {
      @Override
      public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        Object last = event.getPath().getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode) {
          loadChildren((DefaultMutableTreeNode) last);
        }
      }

      @Override
      public void treeWillCollapse(TreeExpansionEvent event) {
        // No-op.
      }
    });

    tree.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        maybeShowPopup(e);
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        maybeShowPopup(e);
      }

      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          openSelected();
        }
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Contextual menu
  // ---------------------------------------------------------------------------

  private void maybeShowPopup(MouseEvent e) {
    if (!e.isPopupTrigger()) {
      return;
    }
    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
    if (path == null) {
      return;
    }
    tree.setSelectionPath(path);
    Object last = path.getLastPathComponent();
    if (!(last instanceof DefaultMutableTreeNode node)
        || !(node.getUserObject() instanceof ExistNode existNode)) {
      return;
    }
    contextMenu(node, existNode).show(tree, e.getX(), e.getY());
  }

  private JPopupMenu contextMenu(DefaultMutableTreeNode node, ExistNode existNode) {
    JPopupMenu menu = new JPopupMenu();
    if (!existNode.collection) {
      menu.add(menuItem("Open", () -> openSelected()));
      menu.add(menuItem("Download…", () -> downloadResource(existNode)));
    }
    if (existNode.collection) {
      menu.add(menuItem("Refresh", () -> reloadNode(node)));
    }
    menu.add(menuItem("Copy Path", () -> copyToClipboard(existNode.path)));
    if (!DB_ROOT.equals(existNode.path)) {
      menu.addSeparator();
      menu.add(menuItem("Delete…", () -> deleteNode(node, existNode)));
    }
    return menu;
  }

  private static JMenuItem menuItem(String label, Runnable action) {
    JMenuItem item = new JMenuItem(label);
    item.addActionListener(e -> action.run());
    return item;
  }

  private void copyToClipboard(String text) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    workspace.showStatusMessage("Copied: " + text);
  }

  private void downloadResource(ExistNode existNode) {
    final ExistClient client = ExistContext.client();
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first.");
      return;
    }
    final Path target = Path.of(System.getProperty("user.home"), "Downloads", existNode.name);
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        // existdb-openapi returns resource content as text (even for "binary" types like .xq),
        // matching how the exist: URL connection reads it; write it back out as UTF-8.
        ExistClient.ResourceContent content = client.getResource(existNode.path);
        Files.write(target, content.content().getBytes(StandardCharsets.UTF_8));
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          workspace.showStatusMessage("Downloaded to " + target);
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Download failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  private void deleteNode(DefaultMutableTreeNode node, ExistNode existNode) {
    final ExistClient client = ExistContext.client();
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first.");
      return;
    }
    String kind = existNode.collection ? "collection (and all its contents)" : "resource";
    int choice = JOptionPane.showConfirmDialog(this,
        "Delete the " + kind + " " + existNode.path + "?",
        "Confirm delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }
    final DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        if (existNode.collection) {
          client.deleteCollection(existNode.path);
        } else {
          client.deleteResource(existNode.path);
        }
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          workspace.showStatusMessage("Deleted " + existNode.path);
          if (parent != null) {
            reloadNode(parent);
          }
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Delete failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  // ---------------------------------------------------------------------------
  // Connection
  // ---------------------------------------------------------------------------

  private void editConnection() {
    ConnectionProfile current = profileStore.load();
    ConnectionProfile edited = ConnectionDialog.edit(ownerFrame(), current);
    if (edited != null) {
      profileStore.save(edited);
      ExistContext.setActiveProfile(edited);
      updateConnectionLabel(edited);
      reloadRoot();
    }
  }

  private void updateConnectionLabel(ConnectionProfile profile) {
    // Name only in the (width-constraining) label; the full base URL lives in the tooltip.
    connectionLabel.setText(profile.getName());
    connectionLabel.setToolTipText(profile.getName() + " — " + profile.getBaseUrl());
  }

  // ---------------------------------------------------------------------------
  // Tree loading
  // ---------------------------------------------------------------------------

  private void reloadRoot() {
    reloadNode(rootNode);
  }

  /** Re-fetches a collection node's children from the server, replacing what's shown. */
  private void reloadNode(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof ExistNode info)) {
      return;
    }
    info.loaded = false;
    info.loading = false;
    node.removeAllChildren();
    addPlaceholder(node);
    treeModel.reload(node);
    // Trigger the load directly: relying on expandPath() to fire treeWillExpand is unreliable
    // when the node is already expanded (the event won't fire, leaving "Loading…" forever).
    loadChildren(node);
    tree.expandPath(new TreePath(node.getPath()));
  }

  private void loadChildren(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof ExistNode)) {
      return;
    }
    final ExistNode existNode = (ExistNode) node.getUserObject();
    if (!existNode.collection || existNode.loaded || existNode.loading) {
      return;
    }
    final ExistClient client = ExistContext.client();
    if (client == null) {
      return;
    }
    existNode.loading = true;
    new SwingWorker<List<ExistClient.ChildEntry>, Void>() {
      @Override
      protected List<ExistClient.ChildEntry> doInBackground() throws Exception {
        return client.listChildren(existNode.path);
      }

      @Override
      protected void done() {
        existNode.loading = false;
        node.removeAllChildren();
        try {
          for (ExistClient.ChildEntry child : get()) {
            String childPath = child.path() != null && !child.path().isEmpty()
                ? child.path()
                : existNode.path.endsWith("/") ? existNode.path + child.name()
                    : existNode.path + "/" + child.name();
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(
                new ExistNode(childPath, child.name(), child.collection()));
            if (child.collection()) {
              addPlaceholder(childNode);
            }
            node.add(childNode);
          }
          existNode.loaded = true;
        } catch (Exception ex) {
          workspace.showErrorMessage("Failed to list " + existNode.path + ": " + ex.getMessage());
        }
        treeModel.reload(node);
        tree.expandPath(new TreePath(node.getPath()));
      }
    }.execute();
  }

  // ---------------------------------------------------------------------------
  // Open
  // ---------------------------------------------------------------------------

  private void openSelected() {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
    if (node == null || !(node.getUserObject() instanceof ExistNode)) {
      return;
    }
    ExistNode existNode = (ExistNode) node.getUserObject();
    if (existNode.collection) {
      tree.expandPath(new TreePath(node.getPath()));
      return;
    }
    try {
      URL url = ExistURLStreamHandler.toUrl(existNode.path);
      if (!workspace.open(url)) {
        workspace.showErrorMessage("Oxygen declined to open " + existNode.path);
      }
    } catch (Exception ex) {
      workspace.showErrorMessage("Failed to open " + existNode.path + ": " + ex.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void addPlaceholder(DefaultMutableTreeNode node) {
    node.add(new DefaultMutableTreeNode("Loading…"));
  }

  private Frame ownerFrame() {
    return (Frame) workspace.getParentFrame();
  }

  /** Node tooltip: the DB path, plus the server base URL on the {@code /db} root. */
  private static String tooltipFor(ExistNode existNode) {
    if (!DB_ROOT.equals(existNode.path)) {
      return existNode.path;
    }
    ExistClient client = ExistContext.client();
    String base = client != null ? client.getProfile().getBaseUrl() : "";
    return base.isEmpty() ? existNode.path : existNode.path + " — " + base;
  }

  /** Renders collections (incl. {@code /db}) as folders, resources as files, with a path tooltip. */
  private static final class ExistTreeCellRenderer extends DefaultTreeCellRenderer {
    @Override
    public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
        boolean expanded, boolean leaf, int row, boolean focus) {
      super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
      if (value instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof ExistNode existNode) {
        // Keep collections looking like folders even when empty (an empty node is otherwise a leaf).
        setIcon(existNode.collection
            ? (expanded ? getDefaultOpenIcon() : getDefaultClosedIcon())
            : getDefaultLeafIcon());
        setToolTipText(tooltipFor(existNode));
      } else {
        setToolTipText(null);
      }
      return this;
    }
  }

  /** Tree node payload: a DB path, its short name, and lazy-load state. */
  private static final class ExistNode {
    final String path;
    final String name;
    final boolean collection;
    boolean loaded;
    boolean loading;

    ExistNode(String path, String name, boolean collection) {
      this.path = path;
      this.name = name;
      this.collection = collection;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
