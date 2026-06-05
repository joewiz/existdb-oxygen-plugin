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
import com.existdb.oxygen.protocol.ExistURLStreamHandler;

import ro.sync.ecss.extensions.api.AuthorDocumentController;
import ro.sync.ecss.extensions.api.node.AuthorNode;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.author.WSAuthorEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.xml.WSXMLTextEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.xml.WSXMLTextNodeRange;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.net.URL;

import javax.swing.SwingWorker;

/**
 * Opens a stored database node's source document over the {@code exist:} scheme and selects the
 * originating element. The node is located by its canonical {@code fn:path()} XPath, resolved on
 * demand from its {@code documentURI} + {@code nodeId} (via {@code util:node-by-id}), then matched in
 * the opened document — in Text mode ({@code findElementsByXPath}) or Author mode
 * ({@code findNodesByXPath}). Shared by the Results view's row links and the Results-pane navigator.
 */
public final class SourceNodeOpener {

  private SourceNodeOpener() {
  }

  /** A resolved navigation target: the source document URL and the XPath to its node (may be null). */
  private record Target(URL url, String xpath) {
  }

  /** Opens {@code documentUri} on {@code serverId} and selects the node identified by {@code nodeId}. */
  public static void open(StandalonePluginWorkspace workspace, String serverId, String documentUri,
      String nodeId) {
    ExistClient client = ExistContext.clientById(serverId);
    if (client == null) {
      workspace.showErrorMessage("No active connection for this result's server.");
      return;
    }
    new SwingWorker<Target, Void>() {
      @Override
      protected Target doInBackground() throws Exception {
        String xpath = client.nodePath(documentUri, nodeId);
        return new Target(ExistURLStreamHandler.toUrl(serverId, documentUri), xpath);
      }

      @Override
      protected void done() {
        try {
          Target target = get();
          workspace.open(target.url());
          select(workspace, target.url(), target.xpath());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          workspace.showErrorMessage("Could not open the source node: " + cause.getMessage());
        }
      }
    }.execute();
  }

  /** Selects the node at {@code xpath} in the just-opened document; best-effort across edit modes. */
  private static void select(StandalonePluginWorkspace workspace, URL url, String xpath) {
    if (xpath == null || xpath.isBlank()) {
      return;
    }
    WSEditor editor = workspace.getEditorAccess(url, StandalonePluginWorkspace.MAIN_EDITING_AREA);
    if (editor == null) {
      return;
    }
    WSEditorPage page = editor.getCurrentPage();
    try {
      if (page instanceof WSXMLTextEditorPage textPage) {
        selectInText(textPage, xpath);
      } else if (page instanceof WSAuthorEditorPage authorPage) {
        selectInAuthor(authorPage, xpath);
      }
    } catch (Exception e) {
      // Selection is best-effort: the document is open even if the node can't be pinpointed
      // (e.g. the XPath engine rejects the EQName syntax, or the document has since changed).
      workspace.showStatusMessage("Opened source document; could not select the node.");
    }
  }

  private static void selectInText(WSXMLTextEditorPage page, String xpath) throws Exception {
    WSXMLTextNodeRange[] ranges = page.findElementsByXPath(xpath);
    if (ranges.length == 0) {
      return;
    }
    WSXMLTextNodeRange range = ranges[0];
    int start = page.getOffsetOfLineStart(range.getStartLine()) + (range.getStartColumn() - 1);
    int end = page.getOffsetOfLineStart(range.getEndLine()) + (range.getEndColumn() - 1);
    page.select(start, end);
    page.scrollCaretToVisible();
  }

  private static void selectInAuthor(WSAuthorEditorPage page, String xpath) throws Exception {
    AuthorDocumentController controller = page.getDocumentController();
    AuthorNode[] nodes = controller.findNodesByXPath(xpath, true, true, true);
    if (nodes.length == 0) {
      return;
    }
    AuthorNode node = nodes[0];
    page.select(node.getStartOffset(), node.getEndOffset() + 1);
    page.scrollCaretToVisible();
  }
}
