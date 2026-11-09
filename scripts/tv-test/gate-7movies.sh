#!/bin/bash
source scripts/tv-test/lib.sh
ADB=/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe; D="$ADB -s emulator-5554"
timeout 20 $D shell input keyevent KEYCODE_HOME; sleep 2
timeout 20 $D shell am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source 7movies >/dev/null 2>&1; sleep 14
GO /movie/1263337; sleep 9; K OK; WAITFS || exit 1; sleep 10
N=$(FRAME_WITH '#lbSubs'); echo "player frame = $N"
echo "1) idle:"; FR=; P top-idle; FR=$N; P frame-idle; bash scripts/tv-test/t.sh shot r4-g-7m-idle
K UP; K UP; echo "2) UP:"; P UP; K DOWN; P DOWN; K DOWN; P "DOWN again (bottom bar?)"; bash scripts/tv-test/t.sh shot r4-g-7m-bottom
echo "3) quality menu:"; K UP; K UP; K RIGHT; P "RIGHT"; K OK; sleep 0.8; P "Quality open"; K DOWN; P DOWN; K DOWN; P DOWN; K OK; sleep 1; P "picked"
echo "subtitles + cues:"; K UP; K UP; K RIGHT; K RIGHT; P "toggle?"; K OK; sleep 0.8; P "subs open"; K RIGHT; K DOWN; P "on English"; K OK; sleep 1; P picked
timeout 40 node scripts/tv-test/cdpf.mjs $N '(()=>{const v=document.querySelector("video");v.currentTime=1200;return "seek"})()'; sleep 7
timeout 40 node scripts/tv-test/cdpf.mjs $N '(()=>{const v=document.querySelector("video");const sh=[...v.textTracks].filter(t=>t.mode==="showing").map(t=>t.label.slice(0,16)+":"+(t.activeCues?t.activeCues.length:0));const dom=[...document.querySelectorAll("[class*=subtitle i],[class*=caption i],[class*=cue i]")].map(e=>(e.innerText||"").trim().slice(0,40)).filter(Boolean).slice(0,3);return JSON.stringify({t:Math.round(v.currentTime),sh,dom})})()'; bash scripts/tv-test/t.sh shot r4-g-7m-cues
echo "4) BACK chain:"; FR=$N; K UP; K UP; sleep 0.5; KB; sleep 0.8; echo "B1 frame: $(bash scripts/tv-test/ps.sh $N | cut -c1-80)"; KB; sleep 1; echo "B2 top: $(FR=; bash scripts/tv-test/ps.sh | cut -c1-80)"; KB; sleep 4; echo "B3: $(timeout 30 node scripts/tv-test/cdp.mjs 'location.pathname+" fs="+!!document.fullscreenElement' 2>&1 | tail -1)"
