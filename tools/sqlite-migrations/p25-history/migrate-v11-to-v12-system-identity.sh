#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
DATABASE_PATH="${1:-$HOME/SDRTrunk/database/sdrtrunk.sqlite}"
APP_HOME="${SDRTRUNK_APP_HOME:-${2:-}}"

if [ -z "$APP_HOME" ]; then
    if [ -d "$SCRIPT_DIR/../../../build/install/sdr-trunk/lib" ]; then
        APP_HOME="$(cd "$SCRIPT_DIR/../../../build/install/sdr-trunk" >/dev/null 2>&1 && pwd)"
    else
        echo "Unable to locate SDRTrunk app home. Pass it as the second argument or set SDRTRUNK_APP_HOME." >&2
        exit 1
    fi
fi

if [ -x "$APP_HOME/runtime/bin/java" ]; then
    JAVACMD="$APP_HOME/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" --enable-native-access=ALL-UNNAMED -cp "$APP_HOME/lib/*" \
    "$SCRIPT_DIR/P25HistoryV11ToV12SystemIdentityMigrator.java" "$DATABASE_PATH"
