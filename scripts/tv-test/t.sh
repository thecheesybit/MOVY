#!/bin/bash
# usage: t.sh open <id> [sleep_secs] | key <KEYCODE...> | shot <name> | eval '<js>'
ADB="/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe"
D="$ADB -s emulator-5554"
case "$1" in
  open)
    $D shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source "$2" >/dev/null
    sleep ${3:-10}
    ;;
  key)
    shift
    [ -z "$NOWAKE" ] && { $D shell input keyevent KEYCODE_SHIFT_LEFT; sleep 0.3; }
    for k in "$@"; do
      case "$k" in
        UP) code="KEYCODE_DPAD_UP" ;;
        DOWN) code="KEYCODE_DPAD_DOWN" ;;
        LEFT) code="KEYCODE_DPAD_LEFT" ;;
        RIGHT) code="KEYCODE_DPAD_RIGHT" ;;
        OK|CENTER) code="KEYCODE_DPAD_CENTER" ;;
        BACK) code="KEYCODE_BACK" ;;
        KEYCODE_*) code="$k" ;;
        DPAD_*) code="KEYCODE_$k" ;;
        *) code="KEYCODE_$k" ;;
      esac
      $D shell input keyevent "$code"
      sleep 0.6
    done
    ;;
  shot)
    mkdir -p "/d/Movy/docs/tv-adapter/screenshots"
    $D exec-out screencap -p > "/d/Movy/docs/tv-adapter/screenshots/$2.png"
    ;;
  eval)
    $D logcat -c
    $D shell "am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_eval '$2'" >/dev/null
    sleep 2
    $D logcat -d -s MovyDebug | tail -5
    ;;
esac
