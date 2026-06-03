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

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * Modal dialog to edit the connection profile (name, base URL, user, password) with a
 * "Test connection" button that hits {@code /api/system/info} and {@code /api/users/whoami}.
 */
public final class ConnectionDialog extends JDialog {

  private final JTextField nameField = new JTextField(24);
  private final JTextField urlField = new JTextField(24);
  private final JTextField userField = new JTextField(24);
  private final JPasswordField passField = new JPasswordField(24);
  private boolean confirmed;

  public ConnectionDialog(Frame owner, ConnectionProfile profile) {
    super(owner, "eXist-db Connection", true);
    nameField.setText(profile.getName());
    urlField.setText(profile.getBaseUrl());
    userField.setText(profile.getUser());
    passField.setText(profile.getPassword());
    passField.setToolTipText(
        "Stored in Oxygen's options using Oxygen's UtilAccess.encrypt(). "
            + "See the README for the link to Oxygen's documentation of that mechanism.");

    setLayout(new BorderLayout(8, 8));
    add(buildForm(), BorderLayout.CENTER);
    add(buildButtons(), BorderLayout.SOUTH);
    pack();
    setLocationRelativeTo(owner);
  }

  private JPanel buildForm() {
    JPanel form = new JPanel(new GridBagLayout());
    addRow(form, 0, "Name:", nameField);
    addRow(form, 1, "Base URL:", urlField);
    addRow(form, 2, "User:", userField);
    addRow(form, 3, "Password:", passField);
    return form;
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

  private JPanel buildButtons() {
    JButton test = new JButton("Test connection");
    test.addActionListener(e -> testConnection());
    JButton ok = new JButton("OK");
    ok.addActionListener(e -> {
      confirmed = true;
      dispose();
    });
    JButton cancel = new JButton("Cancel");
    cancel.addActionListener(e -> dispose());

    JPanel buttons = new JPanel();
    buttons.add(test);
    buttons.add(ok);
    buttons.add(cancel);
    return buttons;
  }

  private void testConnection() {
    ConnectionProfile candidate = toProfile();
    ExistClient client = new ExistClient(candidate);
    try {
      client.systemInfo();
      String who = client.whoamiUser();
      JOptionPane.showMessageDialog(this,
          "Connected. Authenticated as: " + who,
          "Connection OK", JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this,
          "Connection failed:\n" + ex.getMessage(),
          "Connection failed", JOptionPane.ERROR_MESSAGE);
    }
  }

  public ConnectionProfile toProfile() {
    return new ConnectionProfile(
        nameField.getText().trim(),
        urlField.getText().trim(),
        userField.getText().trim(),
        new String(passField.getPassword()));
  }

  public boolean isConfirmed() {
    return confirmed;
  }

  /** Convenience: show the dialog and return the edited profile, or null if cancelled. */
  public static ConnectionProfile edit(Frame owner, ConnectionProfile current) {
    ConnectionDialog dialog = new ConnectionDialog(owner, current);
    dialog.setVisible(true);
    return dialog.isConfirmed() ? dialog.toProfile() : null;
  }
}
