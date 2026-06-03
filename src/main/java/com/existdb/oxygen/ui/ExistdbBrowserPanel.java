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
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
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
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          openSelected();
        }
      }
    });
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
    connectionLabel.setText(profile.getName() + " — " + profile.getBaseUrl());
  }

  // ---------------------------------------------------------------------------
  // Tree loading
  // ---------------------------------------------------------------------------

  private void reloadRoot() {
    ExistNode rootInfo = (ExistNode) rootNode.getUserObject();
    rootInfo.loaded = false;
    rootInfo.loading = false;
    rootNode.removeAllChildren();
    addPlaceholder(rootNode);
    treeModel.reload(rootNode);
    // Trigger the load directly: relying on expandPath() to fire treeWillExpand is unreliable
    // when the node is already expanded (the event won't fire, leaving "Loading…" forever).
    loadChildren(rootNode);
    tree.expandPath(new TreePath(rootNode.getPath()));
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
