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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.swing.SwingWorker;

/**
 * Runs a build command through the user's <em>login interactive</em> shell, so the command resolves
 * exactly as it would in the user's Terminal. This is the key to finding tools that live outside a
 * GUI app's minimal PATH — Homebrew ({@code /opt/homebrew/bin}), asdf shims ({@code ~/.asdf/shims}),
 * nvm, etc. — without hardcoding any disk paths: a login interactive shell sources the user's profile
 * ({@code ~/.zprofile}/{@code ~/.zshrc} or {@code ~/.bash_profile}/{@code ~/.bashrc}), which is where
 * {@code brew shellenv} and the version-manager shims are set up.
 *
 * <p>Output (stdout + stderr, merged) is streamed line-by-line to {@code onLine} on the EDT; the
 * process exit code is delivered to {@code onDone} on the EDT when it finishes.</p>
 */
public final class BuildRunner {

  private BuildRunner() {
  }

  /** Runs {@code command} in {@code dir}, streaming output to {@code onLine}, then {@code onDone(exit)}. */
  public static void run(String command, File dir, Consumer<String> onLine, IntConsumer onDone) {
    new SwingWorker<Integer, String>() {
      @Override
      protected Integer doInBackground() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(shellCommand(command));
        pb.directory(dir);
        pb.redirectErrorStream(true);
        // Never block on stdin: a build tool that reads input would otherwise hang the worker.
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            publish(line);
          }
        }
        return process.waitFor();
      }

      @Override
      protected void process(List<String> lines) {
        lines.forEach(onLine);
      }

      @Override
      protected void done() {
        try {
          onDone.accept(get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          onDone.accept(-1);
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          onLine.accept("Could not start build: " + cause.getMessage());
          onDone.accept(-1);
        }
      }
    }.execute();
  }

  /** {@code [$SHELL, -l, -i, -c, command]} — login + interactive so the user's profile is sourced. */
  static List<String> shellCommand(String command) {
    String shell = System.getenv("SHELL");
    if (shell == null || shell.isBlank()) {
      shell = "/bin/zsh";
    }
    return List.of(shell, "-l", "-i", "-c", command);
  }
}
