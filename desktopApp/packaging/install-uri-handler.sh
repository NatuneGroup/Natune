#!/usr/bin/env bash
# Explicit per-user registration for a portable Linux app bundle. Never requires root.
set -euo pipefail
[[ $# == 1 && -x $1 && $1 == /* ]] || { printf 'Usage: %s /absolute/path/to/Natune\n' "$0" >&2; exit 64; }
[[ $1 != *$'\n'* && $1 != *$'\r'* && $1 != *'%'* ]] || exit 64
launcher="${1//\\/\\\\}"
launcher="${launcher//\"/\\\"}"
launcher="${launcher//\$/\\\$}"
launcher="${launcher//\`/\\\`}"
# The desktop-entry value parser unescapes once before the Exec argument parser.
launcher="${launcher//\\/\\\\}"
applications="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
icons="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"
mkdir -p "$icons"
install -m 644 "$(dirname "${BASH_SOURCE[0]}")/archivetune.png" "$icons/archivetune.png"
mkdir -p "$applications"
printf '[Desktop Entry]\nType=Application\nName=Natune\nExec="%s" %%u\nIcon=archivetune\nTerminal=false\nCategories=AudioVideo;Audio;Player;\nMimeType=x-scheme-handler/archivetune;\nStartupWMClass=Natune\n' \
    "$launcher" > "$applications/archivetune.desktop"
if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database "$applications"; fi
xdg-mime default archivetune.desktop x-scheme-handler/archivetune
