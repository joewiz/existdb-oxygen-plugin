#!/usr/bin/env zsh
# Reinstall the freshly built plugin (exploded) into Oxygen's plugins dir.
# IMPORTANT: Oxygen must be fully quit first — while running it round-trips
# existdb.framework and reverts on-disk edits made underneath it.
set -e
ROOT="${0:A:h}/.."
DEST="/Applications/Oxygen XML Editor/plugins/existdb"
JAR=$(ls "$ROOT"/target/existdb-oxygen-plugin-*-plugin.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then echo "No plugin jar in target/ — run mvn package first." >&2; exit 1; fi
if pgrep -f 'Oxygen XML Editor.app/Contents/MacOS' >/dev/null; then
  echo "Oxygen is still running — quit it (Cmd-Q) before installing." >&2; exit 2
fi
rm -rf "$DEST"/*
unzip -q "$JAR" -d "$DEST" -x 'META-INF/*'
echo "Installed $(basename "$JAR") -> $DEST"
