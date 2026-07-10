#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <sdrtrunk-install-dir> <database>" >&2
  exit 2
fi

INSTALL_DIR="$1"
DATABASE="$2"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ -x "$INSTALL_DIR/runtime/bin/java" ]]; then
  JAVA="$INSTALL_DIR/runtime/bin/java"
else
  JAVA="java"
fi

"$JAVA" --enable-native-access=ALL-UNNAMED -cp "$INSTALL_DIR/lib/*" \
  "$SCRIPT_DIR/P25HistoryResetToV13StatsSchema.java" "$DATABASE"
