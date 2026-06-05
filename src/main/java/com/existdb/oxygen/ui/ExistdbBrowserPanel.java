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
import com.existdb.oxygen.client.MimeTypes;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.ButtonGroup;
import javax.swing.DropMode;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
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

  /** Flavor for an {@link ExistNodeRef} dragged within the JVM (pane → pane). */
  private static final DataFlavor NODE_FLAVOR = new DataFlavor(
      DataFlavor.javaJVMLocalObjectMimeType + ";class=" + ExistNodeRef.class.getName(),
      "eXist tree node");

  /** Oxygen's database-connection icon for the top-level server nodes (matches Data Source Explorer). */
  private static final ImageIcon SERVER_ICON = loadFirstIcon("/images/DBConnection16.png");

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

    // Drag resources/collections within the tree; accept files dropped from Finder / Project.
    tree.setDragEnabled(true);
    tree.setDropMode(DropMode.ON);
    tree.setTransferHandler(new ExistTreeTransferHandler());

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
          // Remove the node in place so the rest of the tree's expansion isn't disturbed.
          if (parent != null) {
            treeModel.removeNodeFromParent(node);
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
  // Drag and drop
  // ---------------------------------------------------------------------------

  /** A dragged tree node, carried in-JVM for pane-to-pane drops. */
  private record ExistNodeRef(String serverId, String path, String name, boolean collection) {
  }

  /**
   * Drag from the tree (a resource or sub-collection) and drop OS files / other nodes onto a
   * collection. Same-server drops relocate via the API (move, or copy with ⌥); dropping files from
   * Finder/Project uploads them. Cross-server drops are handled in a later step.
   */
  private final class ExistTreeTransferHandler extends TransferHandler {
    @Override
    public int getSourceActions(JComponent c) {
      return COPY_OR_MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
      // Only resources and sub-collections are draggable — not the server / db root.
      if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof ExistNode existNode
          && !DB_ROOT.equals(existNode.path)) {
        return new NodeTransferable(new ExistNodeRef(
            existNode.serverId, existNode.path, existNode.name, existNode.collection));
      }
      return null;
    }

    @Override
    public boolean canImport(TransferSupport support) {
      return dropCollection(support) != null
          && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
              || support.isDataFlavorSupported(NODE_FLAVOR));
    }

    @Override
    public boolean importData(TransferSupport support) {
      DefaultMutableTreeNode targetNode = dropCollection(support);
      if (targetNode == null || !(targetNode.getUserObject() instanceof ExistNode target)) {
        return false;
      }
      Transferable transferable = support.getTransferable();
      try {
        if (transferable.isDataFlavorSupported(NODE_FLAVOR)) {
          relocateInternal((ExistNodeRef) transferable.getTransferData(NODE_FLAVOR),
              target, targetNode, support.getDropAction());
          return true;
        }
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
          @SuppressWarnings("unchecked")
          List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
          uploadFiles(files, target, targetNode);
          return true;
        }
      } catch (Exception e) {
        return false;
      }
      return false;
    }

    /** The collection node a drop targets: the node itself if a collection, else its parent. */
    private DefaultMutableTreeNode dropCollection(TransferSupport support) {
      if (!(support.getDropLocation() instanceof JTree.DropLocation location)
          || location.getPath() == null
          || !(location.getPath().getLastPathComponent() instanceof DefaultMutableTreeNode node)
          || !(node.getUserObject() instanceof ExistNode existNode)) {
        return null;
      }
      if (existNode.collection) {
        return node;
      }
      return node.getParent() instanceof DefaultMutableTreeNode parent
          && parent.getUserObject() instanceof ExistNode ? parent : null;
    }
  }

  /** A {@link Transferable} carrying a single {@link ExistNodeRef} under {@link #NODE_FLAVOR}. */
  private static final class NodeTransferable implements Transferable {
    private final ExistNodeRef ref;

    NodeTransferable(ExistNodeRef ref) {
      this.ref = ref;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
      return new DataFlavor[] {NODE_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
      return NODE_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
      if (!NODE_FLAVOR.equals(flavor)) {
        throw new UnsupportedFlavorException(flavor);
      }
      return ref;
    }
  }

  /**
   * Drops a dragged node into a collection: move (a safe copy-then-delete) by default, copy with ⌥.
   * Works within a server (server-side copy) and across servers (a client-side recursive copy).
   */
  private void relocateInternal(ExistNodeRef source, ExistNode target,
      DefaultMutableTreeNode targetNode, int dropAction) {
    final boolean sameServer = source.serverId().equals(target.serverId);
    if (sameServer) {
      if (target.path.equals(parentPath(source.path()))) {
        return; // already in this collection
      }
      if (target.path.equals(source.path()) || target.path.startsWith(source.path() + "/")) {
        workspace.showErrorMessage("Can't move a collection into itself.");
        return;
      }
    }
    final ExistClient sourceClient = ExistContext.clientById(source.serverId());
    final ExistClient targetClient = ExistContext.clientById(target.serverId);
    if (sourceClient == null || targetClient == null) {
      workspace.showInformationMessage("Connect to eXist-db first.");
      return;
    }
    // Same-server: move by default, copy with ⌥. Cross-server is always a copy — never auto-delete
    // a remote server's source on a drag (the Finder "different volume = copy" default; ⌘-to-move
    // isn't reliably available in Swing DnD).
    final boolean copy = !sameServer || dropAction == TransferHandler.COPY;
    // A collection relocate may be a recursive client-side copy, so confirm before a big transfer.
    if (source.collection() && !confirmCollectionRelocate(source.path(), copy)) {
      return;
    }
    performRelocate(sourceClient, targetClient, source, target, targetNode, copy, sameServer);
  }

  private boolean confirmCollectionRelocate(String path, boolean copy) {
    return JOptionPane.showConfirmDialog(this,
        (copy ? "Copy" : "Move") + " the collection " + path + "? "
            + "It is transferred recursively and may take a while.",
        (copy ? "Copy" : "Move") + " collection",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
  }

  private void performRelocate(ExistClient sourceClient, ExistClient targetClient,
      ExistNodeRef source, ExistNode target, DefaultMutableTreeNode targetNode,
      boolean copy, boolean sameServer) {
    final String dest = target.path + "/" + source.name();
    new SwingWorker<Boolean, Void>() {
      @Override
      protected Boolean doInBackground() throws Exception {
        boolean collides = targetClient.listChildren(target.path).stream()
            .anyMatch(c -> c.name().equals(source.name()));
        if (collides && !confirmOverwrite(target.path)) {
          return false;
        }
        if (collides) {
          deleteFrom(targetClient, dest, source.collection()); // user confirmed; clear the target
        }
        relocate(sourceClient, targetClient, source, dest, target.path, copy, sameServer);
        return true;
      }

      @Override
      protected void done() {
        try {
          if (get()) {
            afterRelocate(source, target, targetNode, copy);
          }
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage((copy ? "Copy" : "Move") + " failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  /**
   * Performs the relocation. Same-server uses the server-side {@code move}/{@code copy} (fast,
   * atomic), falling back to a client-side recursive copy if the server lacks the redesigned
   * endpoints (pre-existdb-openapi PR #33). Cross-server is always a client-side copy. A move only
   * deletes the source after the copy fully succeeds, so a failure can't lose data.
   */
  private static void relocate(ExistClient from, ExistClient to, ExistNodeRef source,
      String dest, String parent, boolean copy, boolean sameServer)
      throws java.io.IOException, InterruptedException {
    if (sameServer) {
      try {
        if (copy) {
          to.copy(source.path(), parent);
        } else {
          to.move(source.path(), parent);
        }
        return;
      } catch (ExistHttpException e) {
        // Server lacks the redesigned move/copy — fall back to the client-side copy below.
      }
    }
    crossServerCopy(from, to, source.path(), dest, source.collection());
    if (!copy) {
      deleteFrom(from, source.path(), source.collection());
    }
  }

  /** Recursively copies a resource/collection from one server to another (client-side). */
  private static void crossServerCopy(ExistClient from, ExistClient to, String sourcePath,
      String destPath, boolean collection) throws java.io.IOException, InterruptedException {
    if (collection) {
      to.createCollection(destPath);
      for (ExistClient.ChildEntry child : from.listChildren(sourcePath)) {
        crossServerCopy(from, to, child.path(), destPath + "/" + child.name(), child.collection());
      }
    } else {
      ExistClient.ResourceContent content = from.getResource(sourcePath);
      String mime = content.mimeType() != null ? content.mimeType() : MimeTypes.byName(destPath);
      putResourceTolerant(to, destPath, content.content(), mime);
    }
  }

  /**
   * Stores a resource, falling back to {@code text/plain} if eXist rejects the content as malformed
   * XML — e.g. non-well-formed HTML, which eXist would otherwise try to parse as XML and reject.
   */
  private static void putResourceTolerant(ExistClient client, String path, String content,
      String mime) throws java.io.IOException, InterruptedException {
    try {
      client.putResource(path, content, mime);
    } catch (ExistHttpException e) {
      if ("text/plain".equals(mime) || !isXmlParseError(e)) {
        throw e;
      }
      client.putResource(path, content, "text/plain");
    }
  }

  private static boolean isXmlParseError(ExistHttpException e) {
    String body = e.getResponseBody();
    return body != null
        && (body.contains("XML parser") || body.contains("Content is not allowed in prolog"));
  }

  private static void deleteFrom(ExistClient client, String path, boolean collection)
      throws java.io.IOException, InterruptedException {
    if (collection) {
      client.deleteCollection(path);
    } else {
      client.deleteResource(path);
    }
  }

  /**
   * Refreshes the tree after a successful move/copy, surgically so other servers / expanded
   * collections aren't collapsed: the moved node is dropped in place and the target invalidated.
   */
  private void afterRelocate(ExistNodeRef source, ExistNode target,
      DefaultMutableTreeNode targetNode, boolean copy) {
    workspace.showStatusMessage((copy ? "Copied " : "Moved ")
        + source.path() + " to " + target.path);
    if (!copy) {
      DefaultMutableTreeNode moved = findNode(source.serverId(), source.path());
      if (moved != null && moved.getParent() != null) {
        treeModel.removeNodeFromParent(moved);
      }
    }
    addRelocatedChild(source, target, targetNode);
  }

  /**
   * Shows the relocated item under the target collection without collapsing anything: if the target
   * has been loaded, insert a node for it in place (unless one with that name is already there);
   * otherwise it will appear when the target is first expanded.
   */
  private void addRelocatedChild(ExistNodeRef source, ExistNode target,
      DefaultMutableTreeNode targetNode) {
    if (!target.loaded) {
      return;
    }
    for (int i = 0; i < targetNode.getChildCount(); i++) {
      if (targetNode.getChildAt(i) instanceof DefaultMutableTreeNode existing
          && existing.getUserObject() instanceof ExistNode node
          && node.name.equals(source.name())) {
        return; // already shown (e.g. an overwrite replaced its content, the node stays)
      }
    }
    DefaultMutableTreeNode child = new DefaultMutableTreeNode(new ExistNode(
        target.serverId, target.path + "/" + source.name(), source.name(), source.collection()));
    if (source.collection()) {
      addPlaceholder(child);
    }
    treeModel.insertNodeInto(child, targetNode,
        insertionIndex(targetNode, source.name(), source.collection()));
  }

  /** The sorted insert position in a collection: sub-collections before resources, each by name. */
  private static int insertionIndex(DefaultMutableTreeNode parent, String name, boolean collection) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      if (!(parent.getChildAt(i) instanceof DefaultMutableTreeNode node)
          || !(node.getUserObject() instanceof ExistNode existNode)) {
        continue;
      }
      if (collection && !existNode.collection) {
        return i; // collections sort before the first resource
      }
      if (collection == existNode.collection && existNode.name.compareToIgnoreCase(name) > 0) {
        return i;
      }
    }
    return parent.getChildCount();
  }

  private void uploadFiles(List<File> files, ExistNode target, DefaultMutableTreeNode targetNode) {
    final ExistClient client = ExistContext.clientById(target.serverId);
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first.");
      return;
    }
    final List<String> skipped = new ArrayList<>();
    new SwingWorker<Integer, Void>() {
      @Override
      protected Integer doInBackground() throws Exception {
        Set<String> existing = new HashSet<>();
        for (ExistClient.ChildEntry child : client.listChildren(target.path)) {
          existing.add(child.name());
        }
        if (files.stream().anyMatch(f -> existing.contains(f.getName()))
            && !confirmOverwrite(target.path)) {
          return -1;
        }
        int count = 0;
        for (File file : files) {
          count += uploadRecursive(client, target.path, file, skipped);
        }
        return count;
      }

      @Override
      protected void done() {
        try {
          int count = get();
          if (count < 0) {
            return; // overwrite cancelled
          }
          StringBuilder message = new StringBuilder("Uploaded ").append(count)
              .append(" file(s) to ").append(target.path);
          if (!skipped.isEmpty()) {
            message.append("; skipped ").append(skipped.size())
                .append(" binary file(s) (not yet supported): ").append(String.join(", ", skipped));
          }
          workspace.showStatusMessage(message.toString());
          reloadNode(targetNode);
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Upload failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  /**
   * Uploads a file (or, recursively, a directory) under {@code parentPath}; returns the count
   * uploaded. Binary files are skipped (existdb-openapi's resource PUT is text-only) and their names
   * collected in {@code skipped}.
   */
  private static int uploadRecursive(ExistClient client, String parentPath, File file,
      List<String> skipped) throws java.io.IOException, InterruptedException {
    if (file.isDirectory()) {
      String collection = parentPath + "/" + file.getName();
      client.createCollection(collection);
      int count = 0;
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          count += uploadRecursive(client, collection, child, skipped);
        }
      }
      return count;
    }
    byte[] bytes = Files.readAllBytes(file.toPath());
    if (isBinary(bytes)) {
      skipped.add(file.getName());
      return 0;
    }
    // A known extension picks the right mime; otherwise store as plain text (never XML-parsed).
    String mime = MimeTypes.byName(file.getName());
    putResourceTolerant(client, parentPath + "/" + file.getName(),
        new String(bytes, StandardCharsets.UTF_8), mime != null ? mime : "text/plain");
    return 1;
  }

  /** Heuristic: a NUL byte in the head means binary (existdb-openapi can't store binary as text). */
  private static boolean isBinary(byte[] bytes) {
    int limit = Math.min(bytes.length, 8192);
    for (int i = 0; i < limit; i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  /** Asks (on the EDT) whether to overwrite colliding resources; returns the user's choice. */
  private boolean confirmOverwrite(String collectionPath) {
    final boolean[] confirmed = {false};
    Runnable ask = () -> confirmed[0] = JOptionPane.showConfirmDialog(this,
        "Some items already exist in " + collectionPath + ". Overwrite them?",
        "Confirm overwrite", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        ask.run();
      } else {
        SwingUtilities.invokeAndWait(ask);
      }
    } catch (Exception e) {
      return false;
    }
    return confirmed[0];
  }

  /** The parent collection of a DB path, e.g. {@code /db/a} for {@code /db/a/x.xq}. */
  private static String parentPath(String path) {
    int slash = path.lastIndexOf('/');
    return slash > 0 ? path.substring(0, slash) : DB_ROOT;
  }

  /** Finds the loaded tree node for a given server + DB path, or {@code null}. */
  private DefaultMutableTreeNode findNode(String serverId, String path) {
    java.util.Enumeration<?> nodes = rootNode.breadthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      if (nodes.nextElement() instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof ExistNode existNode
          && serverId.equals(existNode.serverId) && path.equals(existNode.path)) {
        return node;
      }
    }
    return null;
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
        setIcon(iconFor(existNode, expanded));
        setToolTipText(tooltipFor(existNode));
      } else {
        setToolTipText(null);
      }
      return this;
    }

    /** Server nodes get the DB-connection icon; collections folders; resources files. */
    private javax.swing.Icon iconFor(ExistNode existNode, boolean expanded) {
      if (DB_ROOT.equals(existNode.path) && SERVER_ICON != null) {
        return SERVER_ICON;
      }
      if (existNode.collection) {
        return expanded ? getDefaultOpenIcon() : getDefaultClosedIcon();
      }
      return getDefaultLeafIcon();
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
