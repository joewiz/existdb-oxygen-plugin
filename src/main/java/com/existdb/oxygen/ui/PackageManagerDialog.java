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
import com.existdb.oxygen.client.ExistClient.PackageInfo;
import com.existdb.oxygen.client.ExistClient.RemoveResult;
import com.existdb.oxygen.client.ExistClient.UpdateCheck;

import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * Manages the EXPath packages / apps installed on one eXist-db server: lists them, checks the public
 * registry for updates, applies an update, and removes a package (offering a forced removal when
 * other packages depend on it). Installing a locally built {@code .xar} is present but disabled until
 * existdb-openapi implements the multipart upload branch of {@code POST /api/packages/install}.
 *
 * <p>Actions apply immediately against the server (there is no staged "OK to commit"); the dialog's
 * OK/Cancel both simply close it. Built with {@link OxygenUIComponentsFactory} components and hosted
 * in an {@link OKCancelDialog} for native chrome.
 */
public final class PackageManagerDialog {

  private static final String INSTALL_DISABLED_TOOLTIP =
      "Installing a local .xar requires a newer existdb-openapi (multipart upload is not yet "
          + "implemented on the server). Update from the registry is available now.";

  private final transient ExistClient client;
  private final transient PackageTableModel model = new PackageTableModel();
  private final JTable table = OxygenUIComponentsFactory.createTable(model);
  private final transient Frame owner;
  private final String serverName;

  private transient OKCancelDialog host;
  private JButton updateButton;
  private JButton removeButton;
  private JLabel statusLabel;
  /** The registry {@code /find} URL discovered by the last update-check (needed to apply updates). */
  private transient String registryFindUrl;

  private PackageManagerDialog(Frame owner, ExistClient client, String serverName,
      List<PackageInfo> initial) {
    this.owner = owner;
    this.client = client;
    this.serverName = serverName;
    model.setPackages(initial);
  }

  /**
   * Opens the modal Package Manager for {@code serverName}, pre-populated with {@code initial} (the
   * already-fetched installed packages). Subsequent list/update/remove operations run against
   * {@code client} on background threads.
   */
  public static void open(Frame owner, ExistClient client, String serverName,
      List<PackageInfo> initial) {
    new PackageManagerDialog(owner, client, serverName, initial).show();
  }

  private void show() {
    host = OxygenUIComponentsFactory.createOkCancelDialog(
        owner, "Manage Packages — " + serverName, true);
    host.getContentPane().add(buildContent(), BorderLayout.CENTER);
    host.pack();
    host.setLocationRelativeTo(owner);
    host.setVisible(true);
  }

  private JComponent buildContent() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getSelectionModel().addListSelectionListener(e -> updateButtonStates());

    JComponent scroll = OxygenUIComponentsFactory.createScrollPane(table,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scroll.setPreferredSize(new Dimension(620, 320));

    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    content.add(scroll, BorderLayout.CENTER);
    content.add(buildButtons(), BorderLayout.EAST);
    content.add(buildStatusBar(), BorderLayout.SOUTH);
    updateButtonStates();
    return content;
  }

  private JComponent buildButtons() {
    JButton checkButton = button("Check for Updates", this::checkUpdates);
    updateButton = button("Update", this::updateSelected);
    removeButton = button("Remove…", this::removeSelected);
    JButton refreshButton = button("Refresh", this::reload);
    JButton installButton = button("Install .xar…", () -> { });
    installButton.setEnabled(false);
    installButton.setToolTipText(INSTALL_DISABLED_TOOLTIP);

    JPanel buttons = new JPanel();
    buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
    for (JButton b : new JButton[] {checkButton, updateButton, removeButton, installButton,
        refreshButton}) {
      b.setAlignmentX(JComponent.LEFT_ALIGNMENT);
      b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
      buttons.add(b);
      buttons.add(Box.createVerticalStrut(6));
    }
    return buttons;
  }

  private JComponent buildStatusBar() {
    statusLabel = new JLabel(model.getRowCount() + " packages installed");
    return statusLabel;
  }

  private JButton button(String label, Runnable action) {
    return OxygenUIComponentsFactory.createButton(new AbstractAction(label) {
      @Override
      public void actionPerformed(java.awt.event.ActionEvent e) {
        action.run();
      }
    });
  }

  private void updateButtonStates() {
    PackageInfo selected = selectedPackage();
    removeButton.setEnabled(selected != null);
    updateButton.setEnabled(selected != null && !model.availableFor(selected).isEmpty());
  }

  private PackageInfo selectedPackage() {
    int row = table.getSelectedRow();
    return row < 0 ? null : model.packageAt(table.convertRowIndexToModel(row));
  }

  // ---------------------------------------------------------------------------
  // Operations (each runs off the EDT, then refreshes on the EDT)
  // ---------------------------------------------------------------------------

  private void checkUpdates() {
    setBusy(true, "Checking the registry for updates…");
    new SwingWorker<UpdateCheck, Void>() {
      @Override
      protected UpdateCheck doInBackground() throws Exception {
        return client.checkPackageUpdates();
      }

      @Override
      protected void done() {
        try {
          UpdateCheck check = get();
          registryFindUrl = check.registry() + "/find";
          model.setAvailableUpdates(check.updates());
          int n = check.updates().size();
          setStatus(n == 0 ? "No updates available" : n + " update(s) available");
        } catch (Exception ex) {
          error("Could not check for updates", ex);
        } finally {
          setBusy(false, null);
        }
      }
    }.execute();
  }

  private void updateSelected() {
    PackageInfo pkg = selectedPackage();
    if (pkg == null || registryFindUrl == null) {
      return;
    }
    String available = model.availableFor(pkg);
    setBusy(true, "Updating " + pkg.abbrev() + " to " + available + "…");
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        client.installPackage(pkg.name(), registryFindUrl, available);
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          reloadAfter("Updated " + pkg.abbrev() + " to " + available);
        } catch (Exception ex) {
          error("Update failed", ex);
          setBusy(false, null);
        }
      }
    }.execute();
  }

  private void removeSelected() {
    PackageInfo pkg = selectedPackage();
    if (pkg == null) {
      return;
    }
    if (confirm("Remove the package \"" + displayName(pkg) + "\" (" + pkg.abbrev() + ")?",
        "Confirm remove")) {
      remove(pkg, false);
    }
  }

  private void remove(PackageInfo pkg, boolean force) {
    setBusy(true, "Removing " + pkg.abbrev() + "…");
    new SwingWorker<RemoveResult, Void>() {
      @Override
      protected RemoveResult doInBackground() throws Exception {
        return client.removePackage(pkg.abbrev(), force);
      }

      @Override
      protected void done() {
        try {
          RemoveResult result = get();
          if (result.removed()) {
            reloadAfter("Removed " + pkg.abbrev());
          } else if (!result.dependents().isEmpty()) {
            setBusy(false, null);
            offerForcedRemoval(pkg, result);
          } else {
            setBusy(false, result.message());
          }
        } catch (Exception ex) {
          error("Remove failed", ex);
          setBusy(false, null);
        }
      }
    }.execute();
  }

  private void offerForcedRemoval(PackageInfo pkg, RemoveResult result) {
    String dependents = String.join(", ", result.dependents());
    if (confirm("Other packages depend on \"" + displayName(pkg) + "\":\n  " + dependents
        + "\n\nRemove it anyway? Those packages may stop working.", "Dependent packages")) {
      remove(pkg, true);
    }
  }

  private void reload() {
    setBusy(true, "Refreshing…");
    reloadAfter(null);
  }

  /** Re-fetches the installed packages, then sets {@code status} (or the default count) when done. */
  private void reloadAfter(String status) {
    new SwingWorker<List<PackageInfo>, Void>() {
      @Override
      protected List<PackageInfo> doInBackground() throws Exception {
        return client.listPackages();
      }

      @Override
      protected void done() {
        try {
          model.setPackages(get());
          updateButtonStates();
          setStatus(status != null ? status : model.getRowCount() + " packages installed");
        } catch (Exception ex) {
          error("Could not list packages", ex);
        } finally {
          setBusy(false, null);
        }
      }
    }.execute();
  }

  // ---------------------------------------------------------------------------
  // UI helpers
  // ---------------------------------------------------------------------------

  private void setBusy(boolean busy, String status) {
    host.getContentPane().setEnabled(!busy);
    table.setEnabled(!busy);
    if (busy) {
      updateButton.setEnabled(false);
      removeButton.setEnabled(false);
      if (status != null) {
        setStatus(status);
      }
    } else {
      updateButtonStates();
    }
  }

  private void setStatus(String text) {
    statusLabel.setText(text);
  }

  private boolean confirm(String message, String title) {
    return JOptionPane.showConfirmDialog(host, message, title,
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
  }

  private void error(String prefix, Exception ex) {
    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
    setStatus(prefix + ": " + cause.getMessage());
    JOptionPane.showMessageDialog(host, prefix + ":\n" + cause.getMessage(),
        "Package Manager", JOptionPane.ERROR_MESSAGE);
  }

  private static String displayName(PackageInfo pkg) {
    return pkg.title() != null && !pkg.title().isEmpty() ? pkg.title() : pkg.name();
  }

  // ---------------------------------------------------------------------------
  // Table model
  // ---------------------------------------------------------------------------

  /** Installed packages plus, per package, any newer version found by an update-check. */
  private static final class PackageTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Title", "Abbreviation", "Type", "Installed",
        "Available"};

    private final transient List<PackageInfo> packages = new ArrayList<>();
    private final transient Map<String, String> availableByAbbrev = new HashMap<>();

    void setPackages(List<PackageInfo> rows) {
      packages.clear();
      packages.addAll(rows);
      fireTableDataChanged();
    }

    void setAvailableUpdates(List<ExistClient.PackageUpdate> updates) {
      availableByAbbrev.clear();
      for (ExistClient.PackageUpdate u : updates) {
        availableByAbbrev.put(u.abbrev(), u.available());
      }
      fireTableDataChanged();
    }

    PackageInfo packageAt(int row) {
      return packages.get(row);
    }

    /** The newer version available for {@code pkg}, or {@code ""} if it is up to date / unchecked. */
    String availableFor(PackageInfo pkg) {
      String available = availableByAbbrev.get(pkg.abbrev());
      return available != null && !available.equals(pkg.version()) ? available : "";
    }

    @Override
    public int getRowCount() {
      return packages.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
      PackageInfo pkg = packages.get(row);
      return switch (column) {
        case 0 -> displayName(pkg);
        case 1 -> pkg.abbrev();
        case 2 -> pkg.type();
        case 3 -> pkg.version();
        case 4 -> availableFor(pkg);
        default -> "";
      };
    }
  }
}
