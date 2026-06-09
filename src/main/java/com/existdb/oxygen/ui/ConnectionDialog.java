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
import com.existdb.oxygen.model.ConnectionProfile;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * Modal dialog to edit a connection profile (name, URL, user, password). Hosted in Oxygen's
 * {@link OKCancelDialog} (native OK/Cancel + Escape) with factory-built fields; the "Test connection"
 * button hits {@code /api/system/info} and {@code /api/users/whoami}.
 */
public final class ConnectionDialog {

  private final JTextField nameField = OxygenUIComponentsFactory.createTextField();
  private final JTextField urlField = OxygenUIComponentsFactory.createTextField();
  private final JTextField userField = OxygenUIComponentsFactory.createTextField();
  private final JPasswordField passField = new JPasswordField(36);
  private final JCheckBox acceptSelfSignedBox =
      new JCheckBox("Trust self-signed/untrusted certificates (HTTPS)");

  private transient OKCancelDialog host;

  private ConnectionDialog(ConnectionProfile profile) {
    nameField.setColumns(36);
    nameField.setText(profile.getName());
    urlField.setColumns(36);
    urlField.setText(profile.getBaseUrl());
    userField.setColumns(36);
    userField.setText(profile.getUser());
    passField.setText(profile.getPassword());
    passField.setToolTipText(
        "Stored in Oxygen's options using Oxygen's UtilAccess.encrypt(). "
            + "See the README for the link to Oxygen's documentation of that mechanism.");
    acceptSelfSignedBox.setSelected(profile.isAcceptSelfSigned());
    acceptSelfSignedBox.setToolTipText(
        "Enable for an https base URL whose certificate is self-signed or otherwise not trusted "
            + "(eXist's default HTTPS listener and xst both default to a self-signed cert). "
            + "Leave off for production servers with a CA-signed certificate.");
  }

  /** Shows the dialog and returns the edited profile, or null if cancelled/dismissed. */
  public static ConnectionProfile edit(Frame owner, ConnectionProfile current) {
    ConnectionDialog controller = new ConnectionDialog(current);
    controller.host =
        OxygenUIComponentsFactory.createOkCancelDialog(owner, "eXist-db Connection", true);
    controller.host.getContentPane().add(controller.buildContent(), BorderLayout.CENTER);
    controller.host.pack();
    controller.host.setLocationRelativeTo(owner);
    controller.host.setVisible(true);
    return controller.host.getResult() == OKCancelDialog.RESULT_OK ? controller.toProfile() : null;
  }

  private JComponent buildContent() {
    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.add(buildForm(), BorderLayout.CENTER);

    JButton test = OxygenUIComponentsFactory.createButton(new AbstractAction("Test connection") {
      @Override
      public void actionPerformed(ActionEvent e) {
        testConnection();
      }
    });
    JPanel testRow = new JPanel(new BorderLayout());
    testRow.add(test, BorderLayout.WEST);
    content.add(testRow, BorderLayout.SOUTH);
    return content;
  }

  private JPanel buildForm() {
    // Name on top, then the connection details in a titled group — matching Oxygen's Connection
    // dialog (Name + Data Source above a "Connection Details" group).
    JPanel name = new JPanel(new GridBagLayout());
    addRow(name, 0, "Name:", nameField);

    JPanel details = new JPanel(new GridBagLayout());
    details.setBorder(BorderFactory.createTitledBorder("Connection Details"));
    addRow(details, 0, "URL:", urlField);
    addRow(details, 1, "User:", userField);
    addRow(details, 2, "Password:", passField);
    addFieldRow(details, 3, acceptSelfSignedBox);

    JPanel form = new JPanel(new BorderLayout(0, 8));
    form.add(name, BorderLayout.NORTH);
    form.add(details, BorderLayout.CENTER);
    return form;
  }

  /** Adds a full-width row in the field column (no leading label), e.g. a checkbox. */
  private void addFieldRow(JPanel form, int row, JComponent field) {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 8, 4, 8);
    c.gridx = 1;
    c.gridy = row;
    c.anchor = GridBagConstraints.LINE_START;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1.0;
    form.add(field, c);
  }

  private void addRow(JPanel form, int row, String label, JComponent field) {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 8, 4, 8);
    c.gridx = 0;
    c.gridy = row;
    c.anchor = GridBagConstraints.LINE_END;
    form.add(new JLabel(label), c);
    c.gridx = 1;
    c.anchor = GridBagConstraints.LINE_START;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1.0;
    form.add(field, c);
  }

  private void testConnection() {
    ConnectionProfile candidate = toProfile();
    ExistClient client = new ExistClient(candidate);
    String message;
    try {
      client.systemInfo();
      String who = client.whoamiUser();
      message = "Connected to \"" + candidate.getName() + "\". Authenticated as \"" + who + "\".";
    } catch (Exception ex) {
      message = "Connection to \"" + candidate.getName() + "\" failed: " + ex.getMessage();
    }
    // showConfirmDialog (unlike showInformationMessage) lets us title it "Test Connection Result".
    ((StandalonePluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace()).showConfirmDialog(
        "Test Connection Result", message, new String[] {"OK"}, new int[] {0});
  }

  private ConnectionProfile toProfile() {
    return new ConnectionProfile(
        nameField.getText().trim(),
        urlField.getText().trim(),
        userField.getText().trim(),
        new String(passField.getPassword()),
        acceptSelfSignedBox.isSelected());
  }
}
