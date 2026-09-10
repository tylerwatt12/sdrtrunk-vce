#!/bin/sh

launcher_root=$(CDPATH= cd -P "$(dirname "$0")" && pwd) || exit 1
JAVA_HOME="$launcher_root" exec "$launcher_root/bin/sdrtrunk-vce" "$@"
