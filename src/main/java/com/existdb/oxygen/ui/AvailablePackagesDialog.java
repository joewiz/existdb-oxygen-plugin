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
import com.existdb.oxygen.client.ExistClient.AvailablePackage;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * Browses the packages a registry offers (its {@code apps.xml} catalog, fetched client-side) that
 * aren't already installed, and installs a selected one from that registry — the eXist analogue of
 * Oxygen's "Install new add-ons". Hosted in an {@link OKCancelDialog}; on a successful install it
 * runs {@code onInstalled} so the caller (Manage Packages) refreshes its installed list.
 */
public final class AvailablePackagesDialog {

  private final transient ExistClient client;
  private final transient Frame owner;
  private final transient String registryFindUrl;
  private final transient Runnable onInstalled;
  private final AvailableTableModel model;
  private final JTable table;

  private JButton installButton;
  private JLabel statusLabel;

  private AvailablePackagesDialog(Frame owner, ExistClient client, String registryFindUrl,
      List<AvailablePackage> available, Runnable onInstalled) {
    this.owner = owner;
    this.client = client;
    this.registryFindUrl = registryFindUrl;
    this.onInstalled = onInstalled;
    this.model = new AvailableTableModel(available);
    this.table = OxygenUIComponentsFactory.createTable(model);
  }

  /** Opens the modal browser for the {@code available} (not-installed) packages of one registry. */
  public static void open(Frame owner, ExistClient client, String registryFindUrl,
      List<AvailablePackage> available, Runnable onInstalled) {
    new AvailablePackagesDialog(owner, client, registryFindUrl, available, onInstalled).show();
  }

  private void show() {
    OKCancelDialog host =
        OxygenUIComponentsFactory.createOkCancelDialog(owner, "Install New Package", true);
    host.getContentPane().add(buildContent(), BorderLayout.CENTER);
    host.pack();
    host.setLocationRelativeTo(owner);
    host.setVisible(true);
  }

  private JComponent buildContent() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoCreateRowSorter(true);
    table.getSelectionModel().addListSelectionListener(e -> updateInstallEnabled());

    JComponent scroll = OxygenUIComponentsFactory.createScrollPane(table,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scroll.setPreferredSize(new Dimension(620, 320));

    installButton = OxygenUIComponentsFactory.createButton(new javax.swing.AbstractAction("Install") {
      @Override
      public void actionPerformed(java.awt.event.ActionEvent e) {
        installSelected();
      }
    });
    installButton.setEnabled(false);
    JPanel buttons = new JPanel();
    buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
    installButton.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    installButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, installButton.getPreferredSize().height));
    buttons.add(installButton);
    buttons.add(Box.createVerticalStrut(6));

    statusLabel = new JLabel(model.getRowCount() + " package(s) available to install");

    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    content.add(scroll, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.EAST);
    content.add(statusLabel, BorderLayout.SOUTH);
    return content;
  }

  private void updateInstallEnabled() {
    installButton.setEnabled(selected() != null);
  }

  private AvailablePackage selected() {
    int row = table.getSelectedRow();
    return row < 0 ? null : model.at(table.convertRowIndexToModel(row));
  }

  private void installSelected() {
    AvailablePackage pkg = selected();
    if (pkg == null) {
      return;
    }
    installButton.setEnabled(false);
    statusLabel.setText("Installing " + pkg.abbrev() + "…");
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        client.installPackage(pkg.name(), registryFindUrl, pkg.version());
        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          model.remove(pkg);
          statusLabel.setText("Installed " + pkg.abbrev() + " " + pkg.version());
          onInstalled.run();
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          statusLabel.setText("Install failed: " + cause.getMessage());
          PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage(
              "Install failed:\n" + cause.getMessage());
        } finally {
          updateInstallEnabled();
        }
      }
    }.execute();
  }

  /** Table of available (not-installed) packages: Title / Abbreviation / Version / Author. */
  private static final class AvailableTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Title", "Abbreviation", "Version", "Author"};
    private final transient List<AvailablePackage> rows;

    AvailableTableModel(List<AvailablePackage> available) {
      this.rows = new ArrayList<>(available);
    }

    AvailablePackage at(int row) {
      return rows.get(row);
    }

    void remove(AvailablePackage pkg) {
      int index = rows.indexOf(pkg);
      if (index >= 0) {
        rows.remove(index);
        fireTableRowsDeleted(index, index);
      }
    }

    @Override
    public int getRowCount() {
      return rows.size();
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
      AvailablePackage pkg = rows.get(row);
      return switch (column) {
        case 0 -> pkg.title() == null || pkg.title().isEmpty() ? pkg.abbrev() : pkg.title();
        case 1 -> pkg.abbrev();
        case 2 -> pkg.version();
        case 3 -> pkg.author();
        default -> "";
      };
    }
  }
}
