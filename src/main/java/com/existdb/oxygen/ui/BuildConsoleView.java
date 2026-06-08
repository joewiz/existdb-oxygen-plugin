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

import ro.sync.exml.workspace.api.standalone.ui.OxygenUIComponentsFactory;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;

/**
 * A simple read-only console for build output, shown in the "eXist-db Build" docked view. Lines are
 * appended as the build streams them (see {@link BuildRunner}); the caret follows so the latest output
 * stays in view.
 */
public final class BuildConsoleView extends JPanel {

  private static final long serialVersionUID = 1L;

  private final transient JTextArea output = OxygenUIComponentsFactory.createTextArea("");

  public BuildConsoleView() {
    super(new BorderLayout());
    output.setEditable(false);
    output.setLineWrap(false);
    output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, output.getFont().getSize()));
    add(OxygenUIComponentsFactory.createScrollPane(output,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
  }

  /** Clears the console (called at the start of each build). */
  public void clear() {
    output.setText("");
  }

  /** Appends one line, scrolling to keep it visible. */
  public void appendLine(String line) {
    output.append(line);
    output.append("\n");
    output.setCaretPosition(output.getDocument().getLength());
  }
}
