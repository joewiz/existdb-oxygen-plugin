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

import com.existdb.oxygen.model.ConnectionProfile;
import com.existdb.oxygen.project.ExistdbProjectConfig;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Collects the destination for a Project-pane "Upload to eXist…" action: which saved server, and the
 * target collection. When a {@code .existdb.json} was found by the closest-ancestor walk, the server
 * is preselected (matched by base URL) and the target collection is pre-filled from it. Hosted in an
 * {@link OKCancelDialog}; {@link #choose} returns the {@link Result} on OK, or {@code null} on Cancel.
 */
public final class UploadToExistDialog {

  private final transient List<ConnectionProfile> profiles;
  private final transient ExistdbProjectConfig config;
  private final transient String defaultProfileId;
  private final int itemCount;
  private final JComboBox<String> serverCombo;
  private final JTextField targetField;

  /** The user's choice: the destination server profile and target collection. */
  public record Result(ConnectionProfile server, String targetCollection) {
  }

  private UploadToExistDialog(List<ConnectionProfile> profiles, int itemCount,
      ExistdbProjectConfig config, String defaultProfileId) {
    this.profiles = profiles;
    this.itemCount = itemCount;
    this.config = config;
    this.defaultProfileId = defaultProfileId;

    String[] names = profiles.stream().map(ConnectionProfile::getName).toArray(String[]::new);
    serverCombo = OxygenUIComponentsFactory.createComboBox(new DefaultComboBoxModel<>(names));
    serverCombo.setSelectedIndex(preselectServer());

    targetField = OxygenUIComponentsFactory.createTextField();
    targetField.setText(config != null && config.targetCollection() != null
        ? config.targetCollection() : "/db");
    targetField.setColumns(28);
  }

  /**
   * Shows the modal dialog and returns the chosen server + target collection, or {@code null} on
   * Cancel. {@code profiles} must be non-empty.
   */
  public static Result choose(Frame owner, List<ConnectionProfile> profiles, int itemCount,
      ExistdbProjectConfig config, String defaultProfileId) {
    UploadToExistDialog dialog =
        new UploadToExistDialog(profiles, itemCount, config, defaultProfileId);
    OKCancelDialog host =
        OxygenUIComponentsFactory.createOkCancelDialog(owner, "Upload to eXist-db", true);
    host.getContentPane().add(dialog.buildContent(), BorderLayout.CENTER);
    host.pack();
    host.setResizable(false);
    host.setLocationRelativeTo(owner);
    host.setVisible(true);
    if (host.getResult() != OKCancelDialog.RESULT_OK) {
      return null;
    }
    String target = dialog.targetField.getText().trim();
    if (target.isEmpty()) {
      target = "/db";
    }
    // eXist collections live under /db; the server silently reparents anything else (e.g. "/dbfoo"
    // becomes "/db/dbfoo"). Reject that surprise with a clear message instead.
    if (!target.equals("/db") && !target.startsWith("/db/")) {
      PluginWorkspaceProvider.getPluginWorkspace().showErrorMessage(
          "The target collection must be under /db — for example /db/apps/myapp.");
      return null;
    }
    return new Result(dialog.profiles.get(dialog.serverCombo.getSelectedIndex()), target);
  }

  /**
   * The profile to preselect: the one whose base URL matches the descriptor's server (when a
   * descriptor was found), otherwise the configured default server (from Configure eXist-db
   * Connections), falling back to the first.
   */
  private int preselectServer() {
    if (config != null && config.serverUrl() != null) {
      String wanted = stripTrailingSlash(config.serverUrl());
      for (int i = 0; i < profiles.size(); i++) {
        String base = stripTrailingSlash(profiles.get(i).getBaseUrl());
        if (base != null && (base.startsWith(wanted) || wanted.startsWith(base))) {
          return i;
        }
      }
    }
    for (int i = 0; i < profiles.size(); i++) {
      if (profiles.get(i).getId() != null && profiles.get(i).getId().equals(defaultProfileId)) {
        return i;
      }
    }
    return 0;
  }

  private JComponent buildContent() {
    JPanel content = new JPanel(new GridBagLayout());
    content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 6, 4, 6);
    c.anchor = GridBagConstraints.LINE_START;
    c.fill = GridBagConstraints.HORIZONTAL;

    c.gridx = 0;
    c.gridy = 0;
    content.add(new JLabel(itemCount == 1 ? "Upload 1 item to:" : "Upload " + itemCount
        + " items to:"), c);

    c.gridx = 0;
    c.gridy = 1;
    content.add(new JLabel("Server:"), c);
    c.gridx = 1;
    c.weightx = 1.0;
    content.add(serverCombo, c);
    c.weightx = 0;

    c.gridx = 0;
    c.gridy = 2;
    content.add(new JLabel("Target collection:"), c);
    c.gridx = 1;
    c.weightx = 1.0;
    content.add(targetField, c);
    c.weightx = 0;

    if (config != null) {
      c.gridx = 0;
      c.gridy = 3;
      c.gridwidth = 2;
      JLabel note = new JLabel("Defaults from .existdb.json in "
          + config.descriptorDir().getName() + "; paths are mirrored under the collection.");
      note.setFont(note.getFont().deriveFont(Font.ITALIC, note.getFont().getSize() - 1f));
      content.add(note, c);
    }
    return content;
  }

  private static String stripTrailingSlash(String url) {
    if (url == null) {
      return null;
    }
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
