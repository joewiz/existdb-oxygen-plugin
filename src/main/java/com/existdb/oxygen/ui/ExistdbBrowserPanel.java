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
import com.existdb.oxygen.lang.LangServiceSupport;
import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.model.ProfileStore;
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.listeners.WSEditorListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.DropMode;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.ScrollPaneConstants;
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

  /** eXist's "X" logo for the top-level server nodes (falls back to Oxygen's DB-connection icon). */
  private static final ImageIcon SERVER_ICON =
      loadFirstIcon("/images/exist-server.png", "/images/DBConnection16.png");

  /** Maps a file extension (lower-case) to one of Oxygen's bundled file-type icons, and caches them. */
  private static final Map<String, String> TYPE_ICON_RESOURCES = buildTypeIconResources();
  private static final Map<String, ImageIcon> TYPE_ICON_CACHE = new ConcurrentHashMap<>();

  private final transient StandalonePluginWorkspace workspace;
  private final transient ProfileStore profileStore;

  private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("servers");
  private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
  // Built via Oxygen's factory so it inherits the workbench tree's row height, font, and selection.
  private final JTree tree = OxygenUIComponentsFactory.createTree(treeModel);

  /** When on, the active editor's resource is revealed and selected in the tree (like the Project view). */
  private boolean linkWithEditor;
  /** One-shot continuation run after the next lazy child-load completes (drives deep reveals). */
  private transient Runnable pendingAfterLoad;

  public ExistdbBrowserPanel(StandalonePluginWorkspace workspace, ProfileStore profileStore) {
    super(new BorderLayout());
    this.workspace = workspace;
    this.profileStore = profileStore;

    setMinimumSize(new Dimension(150, 0));
    setPreferredSize(new Dimension(260, 400));

    add(buildToolbar(), BorderLayout.NORTH);
    add(OxygenUIComponentsFactory.createScrollPane(tree,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);

    configureTree();
    loadServers();

    // Link with Editor: when enabled, follow editor focus by revealing the matching tree node.
    // Also: when an exist: editor opens, watch for saves so a newly-created resource appears in the
    // tree without a manual Refresh.
    workspace.addEditorChangeListener(new WSEditorChangeListener() {
      @Override
      public void editorSelected(URL editorLocation) {
        revealIfLinked(editorLocation);
      }

      @Override
      public void editorActivated(URL editorLocation) {
        revealIfLinked(editorLocation);
      }

      @Override
      public void editorOpened(URL editorLocation) {
        watchForSaves(editorLocation);
      }
    }, StandalonePluginWorkspace.MAIN_EDITING_AREA);
  }

  /** Attaches a save listener to an {@code exist:} editor so saves refresh its collection in the tree. */
  private void watchForSaves(URL editorLocation) {
    if (editorLocation == null
        || LangServiceSupport.serverId(editorLocation.toString()).isEmpty()) {
      return; // not an exist: resource
    }
    WSEditor editor =
        workspace.getEditorAccess(editorLocation, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor != null) {
      editor.addEditorListener(new WSEditorListener() {
        @Override
        public void editorSaved(int operationType) {
          showSavedResource(editorLocation);
        }
      });
    }
  }

  /** Inserts a just-saved resource into its (already-loaded) parent collection node if not shown. */
  private void showSavedResource(URL editorLocation) {
    String systemId = editorLocation.toString();
    String serverId = LangServiceSupport.serverId(systemId);
    String dbPath = LangServiceSupport.dbPath(systemId);
    if (serverId.isEmpty() || dbPath.isEmpty()) {
      return;
    }
    DefaultMutableTreeNode parentNode = findNode(serverId, parentPath(dbPath));
    if (parentNode == null || !(parentNode.getUserObject() instanceof ExistNode parent)
        || !parent.loaded) {
      return;
    }
    String name = dbPath.substring(dbPath.lastIndexOf('/') + 1);
    if (childNamed(parentNode, name) == null) {
      insertChild(parentNode, serverId, dbPath, name, false);
    }
  }

  private JComponent buildToolbar() {
    Action linkAction = new AbstractAction() {
      {
        putValue(SMALL_ICON, loadFirstIcon("/images/LinkWithEditor16.png"));
        putValue(SHORT_DESCRIPTION, "Link with Editor");
        putValue(SELECTED_KEY, Boolean.FALSE);
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        linkWithEditor = Boolean.TRUE.equals(getValue(SELECTED_KEY));
        if (linkWithEditor) {
          WSEditor editor =
              workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
          if (editor != null) {
            revealIfLinked(editor.getEditorLocation());
          }
        }
      }
    };
    Action searchAction = new AbstractAction() {
      {
        putValue(SMALL_ICON, loadFirstIcon("/images/Search16.png"));
        putValue(SHORT_DESCRIPTION, "Search eXist-db (full-text)");
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        SearchDialog.open(ownerFrame(), profileStore, workspace);
      }
    };
    Action gearAction = new AbstractAction() {
      {
        // The Data Source Explorer's gear-with-menu-lines glyph.
        putValue(SMALL_ICON, loadFirstIcon("/images/OptionsShortcut16.png"));
        putValue(SHORT_DESCRIPTION, "Configure eXist-db Connections");
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        manageServers();
      }
    };

    // Oxygen's factory buttons inherit the workbench's flat rollover (and toggle) styling exactly.
    JButton search = OxygenUIComponentsFactory.createToolbarButton(searchAction, false);
    JButton link = OxygenUIComponentsFactory.createToolbarToggleButton(linkAction, false);
    JButton gear = OxygenUIComponentsFactory.createToolbarButton(gearAction, false);

    JToolBar bar = new JToolBar();
    bar.setFloatable(false);
    bar.setRollover(true);
    bar.add(Box.createHorizontalGlue());
    bar.add(search);
    bar.add(link);
    bar.addSeparator();
    bar.add(gear);
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

  /** Opens the unified server-management window, then rebuilds the tree if the user saved changes. */
  private void manageServers() {
    if (ManageServersDialog.open(ownerFrame(), profileStore)) {
      loadServers();
    }
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
      menu.add(menuItem("New File…", () -> newResource(node, existNode)));
      menu.add(menuItem("New Collection…", () -> newCollection(node, existNode)));
      menu.addSeparator();
      menu.add(menuItem("Refresh", () -> reloadNode(node)));
    } else {
      menu.add(menuItem("Open", this::openSelected));
      menu.add(menuItem("Download…", () -> downloadResource(existNode)));
    }
    menu.add(menuItem("Copy Path", () -> copyToClipboard(existNode.path)));
    if (!existNode.collection) {
      menu.add(menuItem("Copy edit-in-oxygen Link", () -> copyToClipboard(editInOxygenLink(existNode))));
    }
    if (!DB_ROOT.equals(existNode.path)) {
      menu.addSeparator();
      menu.add(menuItem("Rename…", () -> renameNode(node, existNode)));
      menu.add(menuItem("Duplicate", () -> duplicateNode(node, existNode)));
      menu.add(menuItem("Delete…", () -> deleteNode(node, existNode)));
    }
    return menu;
  }

  private static JMenuItem menuItem(String label, Runnable action) {
    JMenuItem item = new JMenuItem(label);
    item.addActionListener(e -> action.run());
    return item;
  }

  /**
   * An {@code edit-in-oxygen:exist://…} deep link for a resource — Oxygen's OS-registered protocol
   * (macOS) opens the wrapped {@code exist:} URL in the running editor, so the link can live in a web
   * page, email, etc.
   */
  private String editInOxygenLink(ExistNode existNode) {
    try {
      return "edit-in-oxygen:"
          + ExistURLStreamHandler.toUrl(existNode.serverId, existNode.path).toExternalForm();
    } catch (IOException e) {
      return "";
    }
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
        // Read bytes correctly (raw bytes for binary, UTF-8 content for text) so downloaded images
        // and other binaries aren't corrupted by a text round-trip.
        ExistClient.ResourceBytes resource = client.readResource(existNode.path);
        Files.write(target, resource.bytes());
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
          offerToCloseDeletedEditors(existNode);
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage("Delete failed: " + cause.getMessage());
        }
      }
    }.execute();
  }

  private void newResource(DefaultMutableTreeNode node, ExistNode coll) {
    final ExistClient client = clientOrWarn(coll.serverId);
    if (client == null) {
      return;
    }
    String name = promptName("New File", "File name (e.g. data.xml):", "");
    if (name == null) {
      return;
    }
    final String path = coll.path + "/" + name;
    final String mime = MimeTypes.byName(name);
    final String content = seedContent(name, mime);
    runMutation(() -> client.putResource(path, content, mime), () -> {
      showNewChild(node, coll, name, false);
      openPath(coll.serverId, path);
      workspace.showStatusMessage("Created " + path);
    }, "Could not create file");
  }

  private void newCollection(DefaultMutableTreeNode node, ExistNode coll) {
    final ExistClient client = clientOrWarn(coll.serverId);
    if (client == null) {
      return;
    }
    String name = promptName("New Collection", "Collection name:", "");
    if (name == null) {
      return;
    }
    final String path = coll.path + "/" + name;
    runMutation(() -> client.createCollection(path), () -> {
      showNewChild(node, coll, name, true);
      workspace.showStatusMessage("Created " + path);
    }, "Could not create collection");
  }

  private void renameNode(DefaultMutableTreeNode node, ExistNode existNode) {
    final ExistClient client = clientOrWarn(existNode.serverId);
    if (client == null) {
      return;
    }
    String name = promptName("Rename", "New name:", existNode.name);
    if (name == null || name.equals(existNode.name)) {
      return;
    }
    runMutation(() -> client.rename(existNode.path, name), () -> {
      relabelInPlace(node, existNode, name);
      workspace.showStatusMessage("Renamed to " + name);
    }, "Rename failed");
  }

  private void duplicateNode(DefaultMutableTreeNode node, ExistNode existNode) {
    final ExistClient client = clientOrWarn(existNode.serverId);
    if (client == null) {
      return;
    }
    final DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
    final String name = uniqueName(parent, copyName(existNode.name));
    runMutation(() -> client.duplicate(existNode.path, name), () -> {
      if (parent != null) {
        selectNode(insertChild(parent, existNode.serverId,
            parentPath(existNode.path) + "/" + name, name, existNode.collection));
      }
      workspace.showStatusMessage("Duplicated as " + name);
    }, "Duplicate failed");
  }

  /**
   * After a delete, offers to close any open editors that pointed at the removed resource (or, for a
   * collection, anything under it). Choosing "No" keeps them open so the user can re-save and thereby
   * re-create the resource — Oxygen otherwise gives no "Missing File" prompt for the {@code exist:}
   * scheme.
   */
  private void offerToCloseDeletedEditors(ExistNode existNode) {
    for (WSEditor editor : affectedEditors(existNode)) {
      String dbPath = LangServiceSupport.dbPath(editor.getEditorLocation().toString());
      // Mirror Oxygen's native Missing-File prompt (wording, Yes = keep open).
      int choice = JOptionPane.showConfirmDialog(this,
          "The following resource no longer exists in the database:\n" + dbPath
              + "\n\nKeep it open in the editor?",
          "Missing File", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
      if (choice == JOptionPane.YES_OPTION) {
        editor.setModified(true); // mark dirty so a save re-creates the resource
      } else {
        editor.close(false);
      }
    }
  }

  private List<WSEditor> affectedEditors(ExistNode existNode) {
    List<WSEditor> affected = new ArrayList<>();
    URL[] locations = workspace.getAllEditorLocations(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (locations == null) {
      return affected;
    }
    for (URL location : locations) {
      if (matchesDeleted(existNode, location)) {
        WSEditor editor =
            workspace.getEditorAccess(location, StandalonePluginWorkspace.MAIN_EDITING_AREA);
        if (editor != null) {
          affected.add(editor);
        }
      }
    }
    return affected;
  }

  private static boolean matchesDeleted(ExistNode existNode, URL location) {
    String systemId = location.toString();
    if (!existNode.serverId.equals(LangServiceSupport.serverId(systemId))) {
      return false;
    }
    String dbPath = LangServiceSupport.dbPath(systemId);
    return existNode.collection
        ? dbPath.equals(existNode.path) || dbPath.startsWith(existNode.path + "/")
        : dbPath.equals(existNode.path);
  }

  // ---------------------------------------------------------------------------
  // Context-menu helpers
  // ---------------------------------------------------------------------------

  private ExistClient clientOrWarn(String serverId) {
    ExistClient client = ExistContext.clientById(serverId);
    if (client == null) {
      workspace.showInformationMessage("Connect to eXist-db first.");
    }
    return client;
  }

  /** Prompts for a single path segment, rejecting blanks and names containing {@code /}. */
  private String promptName(String title, String prompt, String initial) {
    Object input = JOptionPane.showInputDialog(this, prompt, title,
        JOptionPane.PLAIN_MESSAGE, null, null, initial);
    if (input == null) {
      return null;
    }
    String name = input.toString().trim();
    if (name.isEmpty() || name.contains("/")) {
      workspace.showErrorMessage("Please enter a name without a '/'.");
      return null;
    }
    return name;
  }

  /** Minimal valid content for a new resource: empty XML is rejected, so seed a root element. */
  private static String seedContent(String name, String mime) {
    if (mime == null || !mime.contains("xml")) {
      return "";
    }
    String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    String root = base.matches("[A-Za-z_][\\w.-]*") ? base : "root";
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<" + root + "/>\n";
  }

  /** A name not already used by a sibling under {@code parent}, appending {@code -N} as needed. */
  private static String uniqueName(DefaultMutableTreeNode parent, String desired) {
    if (parent == null || !hasChildNamed(parent, desired)) {
      return desired;
    }
    int dot = desired.lastIndexOf('.');
    String stem = dot > 0 ? desired.substring(0, dot) : desired;
    String ext = dot > 0 ? desired.substring(dot) : "";
    for (int i = 2; ; i++) {
      String candidate = stem + "-" + i + ext;
      if (!hasChildNamed(parent, candidate)) {
        return candidate;
      }
    }
  }

  private static boolean hasChildNamed(DefaultMutableTreeNode parent, String name) {
    return childNamed(parent, name) != null;
  }

  private static DefaultMutableTreeNode childNamed(DefaultMutableTreeNode parent, String name) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      if (parent.getChildAt(i) instanceof DefaultMutableTreeNode child
          && child.getUserObject() instanceof ExistNode existNode
          && existNode.name.equals(name)) {
        return child;
      }
    }
    return null;
  }

  /** Inserts a freshly created child into a loaded collection node, sorted, then selects it. */
  private void showNewChild(DefaultMutableTreeNode node, ExistNode coll, String name,
      boolean collection) {
    tree.expandPath(new TreePath(node.getPath()));
    if (!coll.loaded) {
      // Not yet loaded — expanding loads its children asynchronously, which will include the new one.
      return;
    }
    DefaultMutableTreeNode child = childNamed(node, name);
    if (child == null) {
      child = insertChild(node, coll.serverId, coll.path + "/" + name, name, collection);
    }
    selectNode(child);
  }

  private DefaultMutableTreeNode insertChild(DefaultMutableTreeNode parent, String serverId,
      String path, String name, boolean collection) {
    DefaultMutableTreeNode child =
        new DefaultMutableTreeNode(new ExistNode(serverId, path, name, collection));
    if (collection) {
      addPlaceholder(child);
    }
    treeModel.insertNodeInto(child, parent, insertionIndex(parent, name, collection));
    return child;
  }

  /** Selects and scrolls to {@code node} so a freshly created/renamed item is revealed and focused. */
  private void selectNode(DefaultMutableTreeNode node) {
    TreePath path = new TreePath(node.getPath());
    tree.setSelectionPath(path);
    tree.scrollPathToVisible(path);
  }

  /**
   * Renames a node in place: updates its name/path and every descendant's path on the <em>same</em>
   * node objects (so the subtree survives), repositions it for sort order, and re-selects it. Only
   * the renamed node's own expansion is affected — sibling and ancestor branches are untouched, so
   * the rest of the tree never collapses.
   */
  private void relabelInPlace(DefaultMutableTreeNode node, ExistNode existNode, String newName) {
    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
    String newPath = parentPath(existNode.path) + "/" + newName;
    boolean wasExpanded = tree.isExpanded(new TreePath(node.getPath()));
    retag(node, new ExistNode(existNode.serverId, newPath, newName, existNode.collection), existNode);
    retargetDescendants(node, existNode.path, newPath);
    if (parent == null) {
      treeModel.nodeChanged(node);
    } else {
      treeModel.removeNodeFromParent(node);
      treeModel.insertNodeInto(node, parent, insertionIndex(parent, newName, existNode.collection));
    }
    if (wasExpanded) {
      tree.expandPath(new TreePath(node.getPath()));
    }
    selectNode(node);
  }

  /** Rewrites every descendant's path after an ancestor was renamed from {@code oldTop} to {@code newTop}. */
  private static void retargetDescendants(
      DefaultMutableTreeNode node, String oldTop, String newTop) {
    for (int i = 0; i < node.getChildCount(); i++) {
      if (node.getChildAt(i) instanceof DefaultMutableTreeNode child
          && child.getUserObject() instanceof ExistNode c) {
        retag(child, new ExistNode(c.serverId, newTop + c.path.substring(oldTop.length()), c.name,
            c.collection), c);
        retargetDescendants(child, oldTop, newTop);
      }
    }
  }

  /** Swaps a node's {@link ExistNode}, carrying over its loaded/loading state. */
  private static void retag(DefaultMutableTreeNode node, ExistNode replacement, ExistNode old) {
    replacement.loaded = old.loaded;
    replacement.loading = old.loading;
    node.setUserObject(replacement);
  }

  private void openPath(String serverId, String path) {
    try {
      openUrl(ExistURLStreamHandler.toUrl(serverId, path), path);
    } catch (IOException ex) {
      workspace.showErrorMessage("Created, but could not open " + path + ": " + ex.getMessage());
    }
  }

  private void openUrl(URL url, String path) {
    if (!workspace.open(url)) {
      workspace.showErrorMessage("Oxygen declined to open " + path);
    }
  }

  /** Runs a database mutation off the EDT, then {@code onSuccess} on the EDT, or reports the error. */
  private void runMutation(Mutation mutation, Runnable onSuccess, String errorPrefix) {
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        mutation.run();
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          onSuccess.run();
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          workspace.showErrorMessage(errorPrefix + ": " + cause.getMessage());
        }
      }
    }.execute();
  }

  /** A database mutation that may fail with a checked exception. */
  @FunctionalInterface
  private interface Mutation {
    void run() throws IOException, InterruptedException;
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
          populateChildren(node, existNode, get());
        } catch (Exception ex) {
          workspace.showErrorMessage("Failed to list " + existNode.path + ": " + ex.getMessage());
        }
        treeModel.reload(node);
        tree.expandPath(new TreePath(node.getPath()));
        resumeReveal(existNode.loaded);
      }
    }.execute();
  }

  /** Builds child nodes for a freshly listed collection and marks it loaded. */
  private void populateChildren(DefaultMutableTreeNode node, ExistNode existNode,
      List<ExistClient.ChildEntry> entries) {
    for (ExistClient.ChildEntry child : entries) {
      String childPath = childPathOf(existNode, child);
      DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(
          new ExistNode(existNode.serverId, childPath, child.name(), child.collection()));
      if (child.collection()) {
        addPlaceholder(childNode);
      }
      node.add(childNode);
    }
    existNode.loaded = true;
  }

  private static String childPathOf(ExistNode parent, ExistClient.ChildEntry child) {
    if (child.path() != null && !child.path().isEmpty()) {
      return child.path();
    }
    return parent.path.endsWith("/") ? parent.path + child.name() : parent.path + "/" + child.name();
  }

  /**
   * Continues an in-progress "Link with Editor" reveal once a level has loaded — only if it actually
   * loaded, since running the continuation after a failed load would re-trigger the same load.
   */
  private void resumeReveal(boolean loaded) {
    Runnable after = pendingAfterLoad;
    pendingAfterLoad = null;
    if (after != null && loaded) {
      after.run();
    }
  }

  // ---------------------------------------------------------------------------
  // Link with Editor
  // ---------------------------------------------------------------------------

  private void revealIfLinked(URL editorLocation) {
    if (!linkWithEditor || editorLocation == null) {
      return;
    }
    String systemId = editorLocation.toString();
    String serverId = LangServiceSupport.serverId(systemId);
    String dbPath = LangServiceSupport.dbPath(systemId);
    if (serverId.isEmpty() || dbPath.isEmpty()) {
      return; // not an exist:// resource — nothing to reveal
    }
    DefaultMutableTreeNode serverNode = findServerNode(serverId);
    if (serverNode != null) {
      revealStep(serverNode, dbPath);
    }
  }

  private DefaultMutableTreeNode findServerNode(String serverId) {
    for (int i = 0; i < rootNode.getChildCount(); i++) {
      if (rootNode.getChildAt(i) instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof ExistNode existNode
          && existNode.serverId.equals(serverId)) {
        return node;
      }
    }
    return null;
  }

  /**
   * Walks the tree toward {@code targetPath} from a known-ancestor {@code node}, lazily loading and
   * expanding each level: when the node is the target it is selected; otherwise it descends into the
   * child on the path, deferring via {@link #pendingAfterLoad} when a level still needs loading.
   */
  private void revealStep(DefaultMutableTreeNode node, String targetPath) {
    if (!(node.getUserObject() instanceof ExistNode existNode)) {
      return;
    }
    if (existNode.path.equals(targetPath)) {
      selectNode(node);
      return;
    }
    if (!existNode.collection || !targetPath.startsWith(existNode.path + "/")) {
      return; // not an ancestor of the target
    }
    if (!existNode.loaded) {
      pendingAfterLoad = () -> revealStep(node, targetPath);
      tree.expandPath(new TreePath(node.getPath())); // triggers the lazy load
      return;
    }
    tree.expandPath(new TreePath(node.getPath()));
    for (int i = 0; i < node.getChildCount(); i++) {
      if (node.getChildAt(i) instanceof DefaultMutableTreeNode child
          && child.getUserObject() instanceof ExistNode c
          && (c.path.equals(targetPath) || targetPath.startsWith(c.path + "/"))) {
        revealStep(child, targetPath);
        return;
      }
    }
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
      throws IOException, InterruptedException {
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
      String destPath, boolean collection) throws IOException, InterruptedException {
    if (collection) {
      to.createCollection(destPath);
      for (ExistClient.ChildEntry child : from.listChildren(sourcePath)) {
        crossServerCopy(from, to, child.path(), destPath + "/" + child.name(), child.collection());
      }
    } else {
      // Read/write bytes correctly so binary resources (images, PDFs, fonts) aren't corrupted by a
      // text round-trip across servers; textual resources still go through the tolerant text PUT.
      ExistClient.ResourceBytes resource = from.readResource(sourcePath);
      String mime = resource.mimeType() != null ? resource.mimeType() : MimeTypes.byName(destPath);
      if (resource.binary()) {
        to.putResourceBytes(destPath, resource.bytes(), mime);
      } else {
        putResourceTolerant(to, destPath, new String(resource.bytes(), StandardCharsets.UTF_8), mime);
      }
    }
  }

  /**
   * Stores a resource, falling back to {@code text/plain} if eXist rejects the content as malformed
   * XML — e.g. non-well-formed HTML, which eXist would otherwise try to parse as XML and reject.
   */
  private static void putResourceTolerant(ExistClient client, String path, String content,
      String mime) throws IOException, InterruptedException {
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
      throws IOException, InterruptedException {
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
          count += uploadRecursive(client, target.path, file);
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
          workspace.showStatusMessage("Uploaded " + count + " file(s) to " + target.path);
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
   * uploaded. Binary files are stored via the raw streaming PUT; text files via the tolerant text
   * PUT. {@code skipped} collects the names of any files that couldn't be stored.
   */
  private static int uploadRecursive(ExistClient client, String parentPath, File file)
      throws IOException, InterruptedException {
    if (file.isDirectory()) {
      String collection = parentPath + "/" + file.getName();
      client.createCollection(collection);
      int count = 0;
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          count += uploadRecursive(client, collection, child);
        }
      }
      return count;
    }
    byte[] bytes = Files.readAllBytes(file.toPath());
    String path = parentPath + "/" + file.getName();
    String mime = MimeTypes.byName(file.getName());
    if (isBinary(bytes)) {
      client.putResourceBytes(path, bytes, mime); // null mime → application/octet-stream
    } else {
      // A known extension picks the right mime; otherwise store as plain text (never XML-parsed).
      putResourceTolerant(client, path, new String(bytes, StandardCharsets.UTF_8),
          mime != null ? mime : "text/plain");
    }
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

  /** A copy's default name: {@code data.xml} → {@code data-copy.xml}, {@code coll} → {@code coll-copy}. */
  private static String copyName(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) + "-copy" + name.substring(dot) : name + "-copy";
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
      // Inherit the Oxygen tree's font so labels match the rest of the workbench's tree views.
      setFont(t.getFont());
      if (value instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof ExistNode existNode) {
        setIcon(iconFor(existNode, expanded));
        setToolTipText(tooltipFor(existNode));
      } else {
        setToolTipText(null);
      }
      return this;
    }

    /** Server nodes get the eXist icon; collections folders; resources a per-extension type icon. */
    private javax.swing.Icon iconFor(ExistNode existNode, boolean expanded) {
      if (DB_ROOT.equals(existNode.path) && SERVER_ICON != null) {
        return SERVER_ICON;
      }
      if (existNode.collection) {
        return expanded ? getDefaultOpenIcon() : getDefaultClosedIcon();
      }
      return fileIcon(existNode.name, getDefaultLeafIcon());
    }
  }

  private static Map<String, String> buildTypeIconResources() {
    Map<String, String> m = new HashMap<>();
    m.put("xml", "/images/XmlIcon16.png");
    m.put("xq", "/images/XqueryIcon16.png");
    m.put("xql", "/images/XqueryIcon16.png");
    m.put("xqm", "/images/XqueryIcon16.png");
    m.put("xquery", "/images/XqueryIcon16.png");
    m.put("xsd", "/images/XsdIcon16.png");
    m.put("xsl", "/images/XslIcon16.png");
    m.put("xslt", "/images/XslIcon16.png");
    m.put("html", "/images/HtmlIcon16.png");
    m.put("htm", "/images/HtmlIcon16.png");
    m.put("xhtml", "/images/XhtmlIcon16.png");
    m.put("css", "/images/CssIcon16.png");
    m.put("js", "/images/JsIcon16.png");
    m.put("mjs", "/images/JsIcon16.png");
    m.put("json", "/images/JsonIcon16.png");
    m.put("dtd", "/images/DtdIcon16.png");
    m.put("rng", "/images/RngIcon16.png");
    m.put("rnc", "/images/RncIcon16.png");
    m.put("sch", "/images/SchIcon16.png");
    m.put("md", "/images/MDIcon16.png");
    m.put("markdown", "/images/MDIcon16.png");
    m.put("txt", "/images/TxtIcon16.png");
    m.put("sql", "/images/SqlIcon16.png");
    m.put("wsdl", "/images/WsdlIcon16.png");
    m.put("yaml", "/images/YAMLIcon16.png");
    m.put("yml", "/images/YAMLIcon16.png");
    m.put("php", "/images/PhpIcon16.png");
    return m;
  }

  /** The type icon for a resource name by extension, or {@code fallback} when none is mapped. */
  private static javax.swing.Icon fileIcon(String name, javax.swing.Icon fallback) {
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return fallback;
    }
    String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
    String resource = TYPE_ICON_RESOURCES.get(ext);
    if (resource == null) {
      return fallback;
    }
    ImageIcon icon = TYPE_ICON_CACHE.computeIfAbsent(ext, k -> loadFirstIcon(resource));
    return icon != null ? icon : fallback;
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
