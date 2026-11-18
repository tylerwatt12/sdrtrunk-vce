#!/bin/sh

launcher_root=$(CDPATH= cd -P "$(dirname "$0")" && pwd) || exit 1

# Resource limits saved from the web console are applied here, because the JVM heap can only be
# set at start-up. Later -Xmx flags win, and the generated launcher appends SDRTRUNK_VCE_OPTS
# after its own defaults, so this override takes effect without editing the generated script.
limits="$launcher_root/data/conf/resources.properties"
if [ -r "$limits" ]; then
    heap=$(sed -n 's/^maxHeapMb[[:space:]]*=[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p' "$limits" | tail -n 1)
    if [ -n "$heap" ]; then
        SDRTRUNK_VCE_OPTS="-Xmx${heap}m $SDRTRUNK_VCE_OPTS"
    fi
fi
export SDRTRUNK_VCE_OPTS

JAVA_HOME="$launcher_root" exec "$launcher_root/bin/sdrtrunk-vce" "$@"
