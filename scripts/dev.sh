#!/usr/bin/env bash
# Dev helper. Uses the SDK/JDK/emulator from android-dev-toolkit
# (default ../android-dev-toolkit; override with TOOLKIT=/path).
#
#   scripts/dev.sh build      assembleDebug
#   scripts/dev.sh install    installDebug on the emulator (emulator-5554)
#   scripts/dev.sh run        install + launch the app
#   scripts/dev.sh test       JVM unit tests
#   scripts/dev.sh phone      release build (R8) installed on the USB phone for user 0
#   scripts/dev.sh log        logcat for the app only
#   scripts/dev.sh widget     open the emulator's home screen (add the widget by hand)
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLKIT="${TOOLKIT:-$(cd .. && pwd)/android-dev-toolkit}"
[ -f "$TOOLKIT/setup/env.sh" ] || { echo "android-dev-toolkit not found at $TOOLKIT (set TOOLKIT=)" >&2; exit 1; }
. "$TOOLKIT/setup/env.sh"

# Point Gradle at the toolkit SDK (local.properties is gitignored)
grep -qs "^sdk.dir=$ANDROID_SDK_ROOT\$" local.properties || echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
export ANDROID_SERIAL="$EMU_SERIAL"
PKG=net.uncorp.ipeekr

case "${1:-build}" in
  build)   ./gradlew assembleDebug ;;
  install) ./gradlew installDebug ;;
  run)     ./gradlew installDebug && emu_adb shell am start -n "$PKG/.MainActivity" ;;
  test)    ./gradlew testDebugUnitTest ;;
  phone)   ./gradlew assembleRelease
           phone=$(adb devices | awk 'NR>1 && $2=="device" && $1!~/^emulator-/ {print $1; exit}')
           [ -n "$phone" ] || { echo "no USB phone found (adb devices)" >&2; exit 1; }
           adb -s "$phone" install -r --user 0 app/build/outputs/apk/release/app-release.apk ;;
  log)     emu_adb logcat --pid="$(emu_adb shell pidof -s $PKG | tr -d '\r')" ;;
  widget)  emu_adb shell input keyevent KEYCODE_HOME ;;
  *)       sed -n '2,11p' "$0"; exit 1 ;;
esac
