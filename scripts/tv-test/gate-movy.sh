#!/bin/bash
source scripts/tv-test/lib.sh
ADB=/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe; D="$ADB -s emulator-5554"
timeout 20 $D shell input keyevent KEYCODE_HOME; sleep 2
timeout 20 $D shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source movy >/dev/null 2>&1; sleep 14
GO /movie/1263337; sleep 9; K OK; WAITFS || exit 1; sleep 7
echo "1) idle:"; P idle
K UP; K DOWN; echo "2) UP, DOWN:"; P DOWN
echo "3) quality/server/speed menu (opened through its toggle):"
timeout 30 node scripts/tv-test/cdp.mjs '(()=>{const b=[...document.querySelectorAll("#vp-shell .tabbable")].find(x=>/Quality/.test(x.textContent));window.__movyTv.menuToggle=b;b.click();return "ok"})()'; sleep 1; P open; bash scripts/tv-test/t.sh shot r4-g-movy-quality
K DOWN; P "DOWN (quality col)"; K RIGHT; P "RIGHT (server col)"; K DOWN; P DOWN; K LEFT; P "LEFT (quality col)"
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do K DOWN; done; P "to bottom of column"; K DOWN; P "DOWN into speed row"; K RIGHT; P RIGHT; K RIGHT; P RIGHT; K LEFT; P LEFT; K OK; sleep 1; P "picked speed (closes)"
timeout 30 node scripts/tv-test/cdp.mjs 'JSON.stringify({rate:document.querySelector("video").playbackRate})'
echo "4) BACK chain (remote semantics):"; K UP; sleep 0.5; KB; sleep 0.8; echo "B1 $(bash scripts/tv-test/ps.sh | cut -c1-75)"; KB; sleep 0.8; echo "B2 $(bash scripts/tv-test/ps.sh | cut -c1-75)"; KB; sleep 3; echo "B3 $(timeout 30 node scripts/tv-test/cdp.mjs 'location.pathname+" fs="+!!document.fullscreenElement' 2>&1 | tail -1)"
