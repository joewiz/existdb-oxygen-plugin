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
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
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
 * The eXist-db side view: a tree whose top-level nodes are the saved servers (Data Source
 * Explorer-style), each lazily loading its own {@code /db}. Operations route to the node's own
 * server; a settings gear holds connection management and the default-server choice. Double-clicking
 * a resource opens it via the {@code exist://<id>/…} URL scheme so saving writes back to that server.
 */
public final class ExistdbBrowserPanel extends JPanel {

  private static final String DB_ROOT = "/db";

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;

  private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("servers");
  private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
  private final JTree tree = new JTree(treeModel);

  public ExistdbBrowserPanel(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    super(new BorderLayout());
    this.workspace = workspace;
    this.profileStore = profileStore;

    setMinimumSize(new Dimension(150, 0));
    setPreferredSize(new Dimension(260, 400));

    add(buildToolbar(), BorderLayout.NORTH);
    add(new JScrollPane(tree), BorderLayout.CENTER);

    configureTree();
    loadServers();
  }

  private JPanel buildToolbar() {
    JButton gear = new JButton();
    ImageIcon icon = loadFirstIcon("/images/Settings16.png");
    if (icon != null) {
      gear.setIcon(icon);
    } else {
      gear.setText("⚙"); // gear glyph fallback
    }
    gear.setToolTipText("eXist-db settings");
    gear.addActionListener(e -> gearMenu().show(gear, 0, gear.getHeight()));
    JPanel bar = new JPanel(new BorderLayout());
    bar.add(gear, BorderLayout.EAST);
    return bar;
  }

  private void configureTree() {
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.setCellRenderer(new ExistTreeCellRenderer());
    ToolTipManager.sharedInstance().registerComponent(tree);

    tree.addTreeWillExpandListener(new TreeWillExpandListener() {
      @Override
      public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        Object last = event.getPath().getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node) {
          loadChildren(node);
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
  // Servers (top-level nodes)
  // ---------------------------------------------------------------------------

  /** Rebuilds the registry and the top-level server nodes from the saved profiles. */
  private void loadServers() {
    List<ConnectionProfile> profiles = profileStore.loadAll();
    ExistContext.setProfiles(profiles, profileStore.defaultProfileId());
    rootNode.removeAllChildren();
    for (ConnectionProfile profile : profiles) {
      DefaultMutableTreeNode serverNode = new DefaultMutableTreeNode(
          new ExistNode(profile.getId(), DB_ROOT, profile.getName(), true));
      addPlaceholder(serverNode);
      rootNode.add(serverNode);
    }
    treeModel.reload();
  }

  // ---------------------------------------------------------------------------
  // Settings gear
  // ---------------------------------------------------------------------------

  private JPopupMenu gearMenu() {
    JPopupMenu menu = new JPopupMenu();
    String selectedId = selectedServerId();
    menu.add(menuItem("Add server…", this::addServer));
    menu.add(menuItem("Edit server…", () -> editServer(selectedId)));
    menu.add(menuItem("Duplicate server", () -> duplicateServer(selectedId)));
    menu.add(menuItem("Remove server…", () -> removeServer(selectedId)));
    menu.addSeparator();
    menu.add(menuItem("Test connection", () -> testConnection(selectedId)));
    List<ConnectionProfile> profiles = profileStore.loadAll();
    if (profiles.size() > 1) {
      menu.add(defaultServerMenu(profiles));
    }
    return menu;
  }

  private JMenu defaultServerMenu(List<ConnectionProfile> profiles) {
    JMenu submenu = new JMenu("Default server for unsaved queries");
    ButtonGroup group = new ButtonGroup();
    String defaultId = profileStore.defaultProfileId();
    for (ConnectionProfile profile : profiles) {
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(profile.getName());
      item.setSelected(profile.getId().equals(defaultId));
      item.addActionListener(e -> {
        profileStore.setDefaultProfileId(profile.getId());
        ExistContext.setProfiles(profileStore.loadAll(), profile.getId());
      });
      group.add(item);
      submenu.add(item);
    }
    return submenu;
  }

  private void addServer() {
    ConnectionProfile created = ConnectionDialog.edit(ownerFrame(), new ConnectionProfile());
    if (created != null) {
      List<ConnectionProfile> profiles = profileStore.loadAll();
      profiles.add(created);
      profileStore.saveAll(profiles);
      loadServers();
    }
  }

  private void editServer(String serverId) {
    ConnectionProfile profile = profileById(serverId);
    if (profile == null) {
      return;
    }
    ConnectionProfile edited = ConnectionDialog.edit(ownerFrame(), profile);
    if (edited != null) {
      edited.setId(serverId);
      profileStore.saveAll(replace(profileStore.loadAll(), edited));
      loadServers();
    }
  }

  private void duplicateServer(String serverId) {
    ConnectionProfile profile = profileById(serverId);
    if (profile == null) {
      return;
    }
    ConnectionProfile copy = new ConnectionProfile(profile.getName() + " copy",
        profile.getBaseUrl(), profile.getUser(), profile.getPassword(), profile.isAcceptSelfSigned());
    List<ConnectionProfile> profiles = profileStore.loadAll();
    profiles.add(copy);
    profileStore.saveAll(profiles);
    loadServers();
  }

  private void removeServer(String serverId) {
    ConnectionProfile profile = profileById(serverId);
    if (profile == null) {
      return;
    }
    int choice = JOptionPane.showConfirmDialog(this,
        "Remove the server \"" + profile.getName() + "\"?",
        "Remove server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }
    List<ConnectionProfile> profiles = new ArrayList<>(profileStore.loadAll());
    profiles.removeIf(p -> serverId.equals(p.getId()));
    profileStore.saveAll(profiles);
    loadServers();
  }

  private void testConnection(String serverId) {
    final ExistClient client = ExistContext.clientById(serverId);
    if (client == null) {
      workspace.showInformationMessage("Select a server first.");
      return;
    }
    workspace.showStatusMessage("Testing connection…");
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() throws Exception {
        client.systemInfo();
        return client.whoamiUser();
      }

      @Override
      protected void done() {
        try {
          workspace.showInformationMessage("Connected. Authenticated as: " + get());
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Connection failed: " + cause.getMessage());
        }
      }
    }.execute();
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
    if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)
        || !(node.getUserObject() instanceof ExistNode existNode)) {
      return;
    }
    contextMenu(node, existNode).show(tree, e.getX(), e.getY());
  }

  private JPopupMenu contextMenu(DefaultMutableTreeNode node, ExistNode existNode) {
    JPopupMenu menu = new JPopupMenu();
    if (existNode.collection) {
      menu.add(menuItem("Refresh", () -> reloadNode(node)));
    } else {
      menu.add(menuItem("Open", this::openSelected));
      menu.add(menuItem("Download…", () -> downloadResource(existNode)));
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
    final ExistClient client = ExistContext.clientById(existNode.serverId);
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
    final ExistClient client = ExistContext.clientById(existNode.serverId);
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
  // Tree loading
  // ---------------------------------------------------------------------------

  /** Re-fetches a collection node's children from its server, replacing what's shown. */
  private void reloadNode(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof ExistNode info)) {
      return;
    }
    info.loaded = false;
    info.loading = false;
    node.removeAllChildren();
    addPlaceholder(node);
    treeModel.reload(node);
    loadChildren(node);
    tree.expandPath(new TreePath(node.getPath()));
  }

  private void loadChildren(DefaultMutableTreeNode node) {
    if (!(node.getUserObject() instanceof ExistNode existNode)) {
      return;
    }
    if (!existNode.collection || existNode.loaded || existNode.loading) {
      return;
    }
    final ExistClient client = ExistContext.clientById(existNode.serverId);
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
                new ExistNode(existNode.serverId, childPath, child.name(), child.collection()));
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
    if (!(tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node)
        || !(node.getUserObject() instanceof ExistNode existNode)) {
      return;
    }
    if (existNode.collection) {
      tree.expandPath(new TreePath(node.getPath()));
      return;
    }
    try {
      URL url = ExistURLStreamHandler.toUrl(existNode.serverId, existNode.path);
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

  /** The server id of the selected node (walking up to its top-level server), or the first server. */
  private String selectedServerId() {
    if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
        && node.getUserObject() instanceof ExistNode existNode) {
      return existNode.serverId;
    }
    return rootNode.getChildCount() > 0
        && ((DefaultMutableTreeNode) rootNode.getChildAt(0)).getUserObject() instanceof ExistNode first
        ? first.serverId : null;
  }

  private ConnectionProfile profileById(String serverId) {
    if (serverId == null) {
      return null;
    }
    return profileStore.loadAll().stream()
        .filter(p -> serverId.equals(p.getId()))
        .findFirst()
        .orElse(null);
  }

  private static List<ConnectionProfile> replace(List<ConnectionProfile> profiles,
      ConnectionProfile edited) {
    List<ConnectionProfile> out = new ArrayList<>(profiles);
    for (int i = 0; i < out.size(); i++) {
      if (out.get(i).getId() != null && out.get(i).getId().equals(edited.getId())) {
        out.set(i, edited);
      }
    }
    return out;
  }

  private static void addPlaceholder(DefaultMutableTreeNode node) {
    node.add(new DefaultMutableTreeNode("Loading…"));
  }

  private Frame ownerFrame() {
    return (Frame) workspace.getParentFrame();
  }

  private static ImageIcon loadFirstIcon(String... resources) {
    for (String resource : resources) {
      URL url = ExistdbBrowserPanel.class.getResource(resource);
      if (url != null) {
        return new ImageIcon(url);
      }
    }
    return null;
  }

  /** Node tooltip: a server node shows its base URL; other nodes show their DB path. */
  private static String tooltipFor(ExistNode existNode) {
    if (!DB_ROOT.equals(existNode.path)) {
      return existNode.path;
    }
    ExistClient client = ExistContext.clientById(existNode.serverId);
    String base = client != null ? client.getProfile().getBaseUrl() : "";
    return base.isEmpty() ? existNode.name : existNode.name + " — " + base;
  }

  /** Renders collections (incl. server nodes) as folders, resources as files, with a tooltip. */
  private static final class ExistTreeCellRenderer extends DefaultTreeCellRenderer {
    @Override
    public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
        boolean expanded, boolean leaf, int row, boolean focus) {
      super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
      if (value instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof ExistNode existNode) {
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

  /** Tree node payload: which server it belongs to, its DB path/name, and lazy-load state. */
  private static final class ExistNode {
    final String serverId;
    final String path;
    final String name;
    final boolean collection;
    boolean loaded;
    boolean loading;

    ExistNode(String serverId, String path, String name, boolean collection) {
      this.serverId = serverId;
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
