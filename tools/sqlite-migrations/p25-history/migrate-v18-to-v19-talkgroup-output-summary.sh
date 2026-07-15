#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <database> [app-home]" >&2
    exit 2
fi

DATABASE_PATH="$1"
APP_HOME="${SDRTRUNK_APP_HOME:-${2:-}}"

if [ -z "$APP_HOME" ]; then
    for candidate in "$SCRIPT_DIR/../../../build/install/sdrtrunk-vce" "$SCRIPT_DIR/../../../build/install/sdr-trunk"; do
        if [ -d "$candidate/lib" ]; then
            APP_HOME="$(cd "$candidate" >/dev/null 2>&1 && pwd)"
            break
        fi
    done
fi

if [ -z "$APP_HOME" ]; then
    echo "Unable to locate SDRTrunk app home. Pass it as the second argument or set SDRTRUNK_APP_HOME." >&2
    exit 1
fi

if [ -x "$APP_HOME/runtime/bin/java" ]; then
    JAVACMD="$APP_HOME/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" --enable-native-access=ALL-UNNAMED -cp "$APP_HOME/lib/*" \
    "$SCRIPT_DIR/P25HistoryV18ToV19TalkgroupOutputSummaryMigrator.java" "$DATABASE_PATH"
