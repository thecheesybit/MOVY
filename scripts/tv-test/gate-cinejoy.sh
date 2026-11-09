#!/bin/bash
# Gate C for Cinejoy in one go (focus drops after 5 s of silence, so everything runs back to back).
source scripts/tv-test/lib.sh
ADB=/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe; D="$ADB -s emulator-5554"
timeout 20 $D shell input keyevent KEYCODE_HOME; sleep 2
timeout 20 $D shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source cinejoy >/dev/null 2>&1; sleep 14
GO /watch/movie/550; sleep 2; K DOWN; WAITFS || exit 1; sleep 6
echo "1) idle:"; P idle
K UP; K DOWN; echo "2) UP then DOWN (bottom bar):"; P DOWN; bash scripts/tv-test/t.sh shot r4-g-cj-bottom
echo "seek preview in DOM:"; timeout 30 node scripts/tv-test/cdp.mjs '(()=>JSON.stringify([...document.querySelectorAll(".player-root [class*=preview i],.player-root [class*=tooltip i],.player-root [class*=thumb i]")].filter(e=>e.getBoundingClientRect().width>0&&getComputedStyle(e).opacity>0.1&&getComputedStyle(e).visibility!=="hidden").map(e=>String(e.className).slice(0,40)+"|"+(e.innerText||"").slice(0,12))))()'
for i in 1 2 3 4 5 6 7 8; do K RIGHT; if bash scripts/tv-test/ps.sh | grep -q '"focus":"Subtitles"'; then break; fi; done
P toolbar; K OK; sleep 1; echo "3) subtitles menu:"; P open
K DOWN; K DOWN; P "to group (English N)"; K OK; sleep 0.8; K DOWN; P "in group"; K OK; sleep 1.2; P picked
timeout 30 node scripts/tv-test/cdp.mjs '(()=>{const v=document.querySelector("video");v.currentTime=1500;return "seek"})()'; sleep 6
echo "cues:"; timeout 30 node scripts/tv-test/cdp.mjs '(()=>{const v=document.querySelector("video");const sh=[...v.textTracks].filter(t=>t.mode==="showing").map(t=>t.label.slice(0,20)+":"+(t.activeCues?t.activeCues.length:0));const dom=[...document.querySelectorAll(".lp-subtitle")].map(e=>(e.innerText||"").trim().slice(0,40)).filter(Boolean);return JSON.stringify({t:v.currentTime,sh,dom})})()'; bash scripts/tv-test/t.sh shot r4-g-cj-cues
echo "4) BACK chain:"; K UP; K DOWN; P "bar focused"; K BACK; sleep 0.6; P "BACK1 (release)"; K BACK; sleep 2.5; echo "BACK2: $(timeout 30 node scripts/tv-test/cdp.mjs 'location.pathname') $(bash scripts/tv-test/ps.sh | cut -c1-60)"; K BACK; sleep 3; echo "BACK3: $(timeout 30 node scripts/tv-test/cdp.mjs 'location.pathname') $(bash scripts/tv-test/ps.sh | cut -c1-60)"
