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
import com.existdb.oxygen.lang.LangServiceSupport;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import java.awt.Color;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * "Parameter Hints (eXist-db)": shows the signature of the function call enclosing the caret with
 * the active parameter highlighted, via {@code /api/langservice/signature-help}. Triggered manually
 * (menu / shortcut) and automatically as the user types {@code (} or {@code ,} (see the workspace
 * extension). The hint is a non-focus-stealing {@link Popup}, so typing continues uninterrupted.
 */
public final class SignatureHelpAction extends AbstractAction {

  /** Client-property flag so each text component's auto-trigger listeners attach at most once. */
  private static final String WATCH_KEY = "existdb.signatureHelpWatch";

  private final transient StandalonePluginWorkspace workspace;
  private transient Popup popup;
  /** Whether a hint is currently showing — gates the caret-driven active-parameter refresh. */
  private transient boolean showing;

  public SignatureHelpAction(StandalonePluginWorkspace workspace) {
    super("Parameter Hints (eXist-db)");
    this.workspace = workspace;
  }

  @Override
  public void actionPerformed(ActionEvent event) {
    trigger(true);
  }

  /** Whether a hint is on screen (so caret moves should refresh it rather than open a new one). */
  public boolean isShowing() {
    return showing;
  }

  /**
   * Attaches the auto-trigger to a text component (idempotent): typing {@code (}, {@code ,}, or
   * {@code )} re-evaluates signature help (opening, advancing the active parameter, or closing as the
   * server dictates); edits while a hint is up refresh it; losing focus hides it.
   */
  public void watch(JTextComponent component) {
    if (component == null || component.getClientProperty(WATCH_KEY) != null) {
      return;
    }
    component.putClientProperty(WATCH_KEY, Boolean.TRUE);
    // Open the hint when a "(", ",", or ")" is typed.
    component.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        try {
          String inserted = e.getDocument().getText(e.getOffset(), e.getLength());
          if (inserted.indexOf('(') >= 0 || inserted.indexOf(',') >= 0
              || inserted.indexOf(')') >= 0) {
            SwingUtilities.invokeLater(() -> trigger(false));
          }
        } catch (BadLocationException ex) {
          // Ignore; the next relevant keystroke will re-trigger.
        }
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        // Caret movement (below) refreshes/closes while a hint is up.
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        // Attribute-only changes; nothing to do.
      }
    });
    // While a hint is up, any caret move refreshes the active parameter — and closes the hint when
    // the caret leaves the call (the server returns nothing), so it doesn't linger.
    component.addCaretListener(e -> {
      if (showing) {
        SwingUtilities.invokeLater(() -> trigger(false));
      }
    });
    // Dismiss when the editor loses focus (switching tabs, windows, or apps) so the popup — which
    // floats above other windows — never lingers.
    component.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        // Ignore transient focus loss (e.g. to a popup/menu) so the hint can't dismiss itself; only
        // hide on a real change of focus owner — switching tab, window, or application.
        if (!e.isTemporary()) {
          hide();
        }
      }
    });
  }

  /** Hides the current hint, if any. */
  public void hide() {
    showing = false;
    if (popup != null) {
      popup.hide();
      popup = null;
    }
  }

  /**
   * Fetches signature help for the caret in the active XQuery editor and shows (or refreshes) the
   * hint; hides it when the caret isn't inside a call. Safe to call repeatedly (typing/caret moves).
   *
   * @param userInvoked {@code true} for the menu/shortcut action (gives status feedback so the user
   *     isn't left wondering); {@code false} for the silent auto-trigger while typing.
   */
  public void trigger(boolean userInvoked) {
    WSEditor editor = workspace.getCurrentEditorAccess(StandalonePluginWorkspace.MAIN_EDITING_AREA);
    WSTextEditorPage page = textPage(editor);
    if (page == null) {
      hide();
      return;
    }
    final ExistClient client = ExistContext.clientFor(editor.getEditorLocation());
    if (client == null) {
      hide();
      if (userInvoked) {
        workspace.showInformationMessage("Connect to eXist-db first (eXist-db view → Connect…).");
      }
      return;
    }
    final JTextComponent component = (JTextComponent) page.getTextComponent();
    final int caret = component.getCaretPosition();
    final String text = component.getText();
    final String moduleLoadPath = LangServiceSupport.moduleLoadPath(editor.getEditorLocation());
    // Compute the 0-based line/column directly from the caret offset. Signature-help needs the exact
    // position (the column right after the call's "("); Oxygen's getColumnOfOffset is off by one for
    // this purpose, and hover only tolerates it because it snaps to the surrounding token.
    int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
    final int column = caret - lineStart;
    int newlines = 0;
    for (int i = 0; i < caret && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        newlines++;
      }
    }
    final int line = newlines;

    new SwingWorker<ExistClient.SignatureHelp, Void>() {
      @Override
      protected ExistClient.SignatureHelp doInBackground() throws Exception {
        return client.signatureHelp(text, line, column, moduleLoadPath);
      }

      @Override
      protected void done() {
        try {
          ExistClient.SignatureHelp help = get();
          if (help == null || help.signatures().isEmpty()) {
            hide();
            if (userInvoked) {
              workspace.showStatusMessage("eXist-db: no function call at the caret.");
            }
          } else {
            showHint(component, caret, help);
          }
        } catch (Exception e) {
          hide();
          if (userInvoked) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            workspace.showErrorMessage("eXist-db parameter hints failed: " + cause.getMessage());
          }
        }
      }
    }.execute();
  }

  private void showHint(JTextComponent component, int caret, ExistClient.SignatureHelp help) {
    if (!component.isShowing()) {
      return;
    }
    int sigIndex = Math.max(0, Math.min(help.activeSignature(), help.signatures().size() - 1));
    ExistClient.SignatureInfo signature = help.signatures().get(sigIndex);
    JLabel label = new JLabel(renderHtml(signature, help.activeParameter()));
    label.setOpaque(true);
    label.setBackground(new Color(0xFD, 0xFD, 0xF5));
    label.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(new Color(0xC8, 0xCE, 0xD6)),
        BorderFactory.createEmptyBorder(3, 6, 3, 6)));
    try {
      Rectangle2D r = component.modelToView2D(caret);
      Point origin = component.getLocationOnScreen();
      int x = origin.x + (int) r.getX();
      int height = label.getPreferredSize().height;
      // Prefer just above the caret's line (so it doesn't cover what's being typed), but drop below
      // when there's no room above — e.g. a call on the first line, where above is off-screen.
      int above = origin.y + (int) r.getY() - height - 2;
      int below = origin.y + (int) (r.getY() + r.getHeight()) + 2;
      int y = above >= origin.y ? above : below;
      hide();
      popup = PopupFactory.getSharedInstance().getPopup(component, label, x, y);
      popup.show();
      showing = true;
    } catch (BadLocationException e) {
      hide();
    }
  }

  /** The signature as monospace HTML with the active parameter bolded. */
  private static String renderHtml(ExistClient.SignatureInfo signature, int activeParameter) {
    String label = signature.label();
    List<ExistClient.ParameterInfo> params = signature.parameters();
    String highlighted = escape(label);
    if (activeParameter >= 0 && activeParameter < params.size()) {
      String param = params.get(activeParameter).label();
      if (!param.isEmpty()) {
        String escapedParam = escape(param);
        int at = highlighted.indexOf(escapedParam);
        if (at >= 0) {
          highlighted = highlighted.substring(0, at) + "<b>" + escapedParam + "</b>"
              + highlighted.substring(at + escapedParam.length());
        }
      }
    }
    return "<html><span style='font-family:monospace;font-size:11px'>" + highlighted
        + "</span></html>";
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static WSTextEditorPage textPage(WSEditor editor) {
    if (editor == null) {
      return null;
    }
    WSEditorPage current = editor.getCurrentPage();
    return current instanceof WSTextEditorPage textPage ? textPage : null;
  }
}
