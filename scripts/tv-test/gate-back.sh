#!/bin/bash
# usage: gate-back.sh <source> <path-with-play> : BACK chain with remote semantics (no Shift wake) from a fullscreen player.
source scripts/tv-test/lib.sh
ADB=/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe; D="$ADB -s emulator-5554"
timeout 20 $D shell input keyevent KEYCODE_HOME; sleep 2
timeout 20 $D shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source $1 >/dev/null 2>&1; sleep 14
GO $2; sleep 9; K OK; WAITFS || exit 1; sleep 7
K UP; sleep 0.5; KB; sleep 0.8; echo "B1 $(bash scripts/tv-test/ps.sh | cut -c1-75)"; KB
for i in 1 2 3 4 5 6; do sleep 1.5; echo "B2+$i $(timeout 30 node scripts/tv-test/cdp.mjs 'location.pathname+" fs="+!!document.fullscreenElement' 2>&1 | tail -1)"; done
