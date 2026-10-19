#!/usr/bin/env bash
set -euo pipefail
collect_screens() {
  mkdir -p ui-screenshots
  adb pull /sdcard/Android/data/kr.local.settlement/files/screenshots ui-screenshots || true
}
# android-emulator-runner shuts the emulator down when this script exits.
trap collect_screens EXIT
gradle --no-daemon connectedDebugAndroidTest
