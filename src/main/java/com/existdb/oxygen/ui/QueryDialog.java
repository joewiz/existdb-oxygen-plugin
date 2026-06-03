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

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/**
 * A simple XQuery runner: type a query, execute it against the active connection via
 * {@code POST /api/query}, and show the first page of results from {@code /api/query/{id}/results}.
 */
public final class QueryDialog extends JDialog {

  private static final int PAGE_SIZE = 100;

  private final JTextArea queryArea = new JTextArea("(1 to 10)", 8, 60);
  private final JTextArea resultsArea = new JTextArea(12, 60);
  private final JLabel status = new JLabel(" ");

  public QueryDialog(Frame owner) {
    super(owner, "Run XQuery — eXist-db", false);
    resultsArea.setEditable(false);

    JButton run = new JButton("Run");
    run.addActionListener(e -> run());
    JPanel south = new JPanel(new BorderLayout());
    south.add(status, BorderLayout.CENTER);
    south.add(run, BorderLayout.EAST);

    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
        new JScrollPane(queryArea), new JScrollPane(resultsArea));
    split.setResizeWeight(0.4);

    setLayout(new BorderLayout(4, 4));
    add(split, BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);
    setPreferredSize(new Dimension(640, 480));
    pack();
    setLocationRelativeTo(owner);
  }

  private void run() {
    final ExistClient client = ExistContext.client();
    if (client == null) {
      status.setText("No active connection. Connect via the eXist-db view first.");
      return;
    }
    final String query = queryArea.getText();
    status.setText("Running…");
    resultsArea.setText("");

    new SwingWorker<String, Void>() {
      private int total;

      @Override
      protected String doInBackground() throws Exception {
        ExistClient.QueryHandle handle = client.runQuery(query, null);
        total = handle.items();
        if (handle.cursor() == null) {
          return "";
        }
        try {
          String body = client.fetchResultsRaw(handle.cursor(), 1, PAGE_SIZE, "adaptive");
          return formatResults(body);
        } finally {
          client.closeCursor(handle.cursor());
        }
      }

      @Override
      protected void done() {
        try {
          resultsArea.setText(get());
          int shown = Math.min(total, PAGE_SIZE);
          status.setText(total + " item(s); showing " + shown
              + (total > PAGE_SIZE ? " (first page)" : ""));
        } catch (Exception ex) {
          Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
          status.setText("Error");
          resultsArea.setText(cause.getMessage());
        }
      }
    }.execute();
  }

  /** Turns the results JSON array into one line per item ({@code value}). */
  private static String formatResults(String body) {
    StringBuilder sb = new StringBuilder();
    JSONArray items = new JSONArray(body);
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      sb.append(item.optString("value", item.toString())).append('\n');
    }
    return sb.toString();
  }
}
