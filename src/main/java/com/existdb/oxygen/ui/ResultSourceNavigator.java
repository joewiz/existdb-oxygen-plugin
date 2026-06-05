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

import ro.sync.document.DocumentPositionedInfo;
import ro.sync.exml.workspace.api.results.ResultsTabEvent;
import ro.sync.exml.workspace.api.results.ResultsTabEventHandler;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles "jump to source" from the eXist-db XQuery Results view: when a result row that corresponds
 * to a stored database node is activated (double-clicked), it opens the node's source document over
 * the {@code exist:} scheme and selects the originating element.
 *
 * <p>The element is located by its canonical {@code fn:path()} XPath, resolved on demand from the
 * node's {@code documentURI} + {@code nodeId} (via {@code util:node-by-id}), then matched in the
 * opened document with Oxygen's {@code findElementsByXPath} (Text mode) or {@code findNodesByXPath}
 * (Author mode). Rows without a source node (atomic / in-memory results) are left to Oxygen's default
 * navigation, which targets the serialized-output editor.
 */
public final class ResultSourceNavigator implements ResultsTabEventHandler {

  private final transient StandalonePluginWorkspace workspace;
  private final transient Map<DocumentPositionedInfo, NodeRef> rows = new HashMap<>();

  /** A stored node's coordinates: which server, which document, and the eXist node id within it. */
  public record NodeRef(String serverId, String documentUri, String nodeId) {
  }

  public ResultSourceNavigator(StandalonePluginWorkspace workspace) {
    this.workspace = workspace;
  }

  /** Replaces the row → source-node mapping for the current result set. */
  public void setRows(Map<DocumentPositionedInfo, NodeRef> next) {
    rows.clear();
    rows.putAll(next);
  }

  @Override
  public boolean handle(ResultsTabEvent event) {
    if (event.getEventType() != ResultsTabEvent.ResultsTabEventType.DEFAULT_ACTION) {
      return false;
    }
    NodeRef ref = rows.get(event.getResultItem());
    if (ref == null) {
      return false; // atomic / in-memory result — let Oxygen navigate to the results editor
    }
    navigate(ref);
    return true;
  }

  private void navigate(NodeRef ref) {
    SourceNodeOpener.open(workspace, ref.serverId(), ref.documentUri(), ref.nodeId());
  }
}
