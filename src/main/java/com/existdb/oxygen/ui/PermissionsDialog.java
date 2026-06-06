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

import ro.sync.exml.workspace.api.standalone.ui.OKCancelDialog;
import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Edits a resource/collection's owner, group, and Unix-style permission mode — modeled on eXide's
 * properties dialog: owner/group are dropdowns of the server's accounts, and the User/Group/Other
 * columns each carry read/write/execute plus the special bit (setuid / setgid / sticky). Built with
 * {@link OxygenUIComponentsFactory} components and hosted in {@link OKCancelDialog}; {@link #edit}
 * returns the new {@link ExistClient.Permissions} on OK, or {@code null} on Cancel.
 */
public final class PermissionsDialog {

  /** Column scopes (User/Group/Other) with their special-bit label and the on/off mode chars. */
  private static final String[] SCOPES = {"User", "Group", "Other"};
  private static final String[] SPECIAL_LABELS = {"setuid", "setgid", "sticky"};
  private static final char[] SPECIAL_ON = {'s', 's', 't'};
  private static final char[] SPECIAL_OFF = {'S', 'S', 'T'};

  private final transient JComboBox<String> ownerCombo;
  private final transient JComboBox<String> groupCombo;
  // [scope][bit]: bit 0=read, 1=write, 2=execute, 3=special (setuid/setgid/sticky).
  private final transient JCheckBox[][] bits = new JCheckBox[3][4];

  private PermissionsDialog(ExistClient.Permissions current, List<String> users,
      List<String> groups) {
    ownerCombo = OxygenUIComponentsFactory.createComboBox(
        new DefaultComboBoxModel<>(withCurrent(users, current.owner())));
    ownerCombo.setSelectedItem(current.owner());
    groupCombo = OxygenUIComponentsFactory.createComboBox(
        new DefaultComboBoxModel<>(withCurrent(groups, current.group())));
    groupCombo.setSelectedItem(current.group());

    String mode = current.mode();
    for (int scope = 0; scope < 3; scope++) {
      int base = scope * 3;
      char execChar = charAt(mode, base + 2);
      bits[scope][0] = checkBox("read", charAt(mode, base) == 'r');
      bits[scope][1] = checkBox("write", charAt(mode, base + 1) == 'w');
      bits[scope][2] = checkBox("execute", execChar == 'x' || execChar == SPECIAL_ON[scope]);
      bits[scope][3] = checkBox(SPECIAL_LABELS[scope],
          execChar == SPECIAL_ON[scope] || execChar == SPECIAL_OFF[scope]);
    }
  }

  /**
   * Shows the modal dialog for {@code path}, pre-filled from {@code current} with owner/group chosen
   * from {@code users}/{@code groups}; returns the edited permissions on OK, or {@code null} on Cancel.
   */
  public static ExistClient.Permissions edit(Frame owner, String path,
      ExistClient.Permissions current, List<String> users, List<String> groups) {
    PermissionsDialog dialog = new PermissionsDialog(current, users, groups);
    OKCancelDialog host =
        OxygenUIComponentsFactory.createOkCancelDialog(owner, "Permissions — " + path, true);
    host.getContentPane().add(dialog.buildContent(), BorderLayout.CENTER);
    host.pack();
    host.setResizable(false);
    host.setLocationRelativeTo(owner);
    host.setVisible(true);
    if (host.getResult() == OKCancelDialog.RESULT_OK) {
      return new ExistClient.Permissions(
          (String) dialog.ownerCombo.getSelectedItem(),
          (String) dialog.groupCombo.getSelectedItem(),
          dialog.buildMode());
    }
    return null;
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
    content.add(new JLabel("Owner:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    content.add(ownerCombo, c);
    c.gridwidth = 1;
    c.gridx = 0;
    c.gridy = 1;
    content.add(new JLabel("Group:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    content.add(groupCombo, c);
    c.gridwidth = 1;

    content.add(buildPermissionsGrid(), gridConstraints(0, 2, 3));
    return content;
  }

  private JComponent buildPermissionsGrid() {
    JPanel grid = new JPanel(new GridBagLayout());
    grid.setBorder(BorderFactory.createTitledBorder("Permissions"));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 10, 4, 10);
    c.anchor = GridBagConstraints.LINE_START;
    c.weightx = 1.0;
    c.fill = GridBagConstraints.HORIZONTAL;

    for (int scope = 0; scope < 3; scope++) {
      c.gridx = scope;
      c.gridy = 0;
      JLabel header = new JLabel(SCOPES[scope]);
      header.setFont(header.getFont().deriveFont(Font.BOLD));
      grid.add(header, c);
      for (int bit = 0; bit < 4; bit++) {
        c.gridy = 1 + bit;
        grid.add(bits[scope][bit], c);
      }
    }
    return grid;
  }

  private static GridBagConstraints gridConstraints(int x, int y, int width) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = x;
    c.gridy = y;
    c.gridwidth = width;
    c.insets = new Insets(8, 6, 0, 6);
    c.anchor = GridBagConstraints.LINE_START;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1.0;
    return c;
  }

  /** Builds the 9-char symbolic mode (e.g. {@code rwsr-sr-t}) from the checkbox grid. */
  private String buildMode() {
    StringBuilder mode = new StringBuilder(9);
    for (int scope = 0; scope < 3; scope++) {
      boolean execute = bits[scope][2].isSelected();
      boolean special = bits[scope][3].isSelected();
      mode.append(bits[scope][0].isSelected() ? 'r' : '-');
      mode.append(bits[scope][1].isSelected() ? 'w' : '-');
      if (special) {
        mode.append(execute ? SPECIAL_ON[scope] : SPECIAL_OFF[scope]);
      } else {
        mode.append(execute ? 'x' : '-');
      }
    }
    return mode.toString();
  }

  private static JCheckBox checkBox(String label, boolean selected) {
    JCheckBox box = new JCheckBox(label);
    box.setSelected(selected);
    return box;
  }

  private static char charAt(String mode, int index) {
    return index < mode.length() ? mode.charAt(index) : '-';
  }

  /** The names plus {@code current} if it isn't already present (so the current value is selectable). */
  private static String[] withCurrent(List<String> names, String current) {
    List<String> all = new ArrayList<>(names);
    if (current != null && !current.isEmpty() && !all.contains(current)) {
      all.add(0, current);
    }
    return all.toArray(new String[0]);
  }
}
