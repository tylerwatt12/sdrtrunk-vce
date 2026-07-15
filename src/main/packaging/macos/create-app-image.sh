#!/bin/sh

set -eu

jpackage_executable="$1"
info_plist="$2"
shift 2

"$jpackage_executable" "$@"

# SDR sample processing is continuous, latency-sensitive work. Prevent macOS from reducing process priority and
# throttling timers/I/O when the application is obscured or not frontmost.
/usr/bin/plutil -insert NSAppSleepDisabled -bool true "$info_plist"

# jpackage applies an ad-hoc signature before this customization. Re-sign the bundle after changing its sealed plist.
app_bundle=${info_plist%/Contents/Info.plist}
/usr/bin/codesign --force --sign - "$app_bundle"
