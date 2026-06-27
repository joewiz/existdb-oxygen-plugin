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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * A folder icon dimmed and overlaid with a small padlock at the bottom-right, marking a collection
 * the connected user can't enter (existdb-openapi#72's {@code accessible: false}). Same footprint as
 * the base icon, so it's a drop-in in the tree renderer.
 */
final class LockedFolderIcon implements Icon {

  private static final Color SHACKLE = new Color(0x55, 0x55, 0x55);
  private static final Color BODY = new Color(0x6E, 0x6E, 0x6E);

  private final Icon base;

  LockedFolderIcon(Icon base) {
    this.base = base;
  }

  @Override
  public void paintIcon(Component c, Graphics g, int x, int y) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      // Dim the folder so it reads as inaccessible.
      g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
      base.paintIcon(c, g2, x, y);
      g2.setComposite(AlphaComposite.SrcOver);

      // A small padlock in the bottom-right corner: an arc shackle over a rounded body, each with a
      // white halo so it stays legible over the folder and a selected (highlighted) row.
      int bodyW = 7;
      int bodyH = 5;
      int bx = x + base.getIconWidth() - bodyW;
      int by = y + base.getIconHeight() - bodyH;

      g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.setColor(Color.WHITE);
      g2.drawArc(bx + 1, by - 4, bodyW - 3, 6, 0, 180);
      g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.setColor(SHACKLE);
      g2.drawArc(bx + 1, by - 4, bodyW - 3, 6, 0, 180);

      g2.setColor(Color.WHITE);
      g2.fillRoundRect(bx - 1, by - 1, bodyW + 2, bodyH + 2, 3, 3);
      g2.setColor(BODY);
      g2.fillRoundRect(bx, by, bodyW, bodyH, 2, 2);
    } finally {
      g2.dispose();
    }
  }

  @Override
  public int getIconWidth() {
    return base.getIconWidth();
  }

  @Override
  public int getIconHeight() {
    return base.getIconHeight();
  }
}
