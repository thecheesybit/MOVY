#!/bin/bash
# usage: r5-mic.sh <site> "<phrase>": debug voice stub -> drawer Search -> BACK (keyboard off) -> RIGHT to the mic -> OK -> results focus. Screenshots r5-L3a-<site>-mic-*.
cd /d/Movy
ADB="/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe"; D="$ADB -s emulator-5554"
S=$1; W=${2:-fight club}
st() { node scripts/tv-test/cdp.mjs 'var i=document.querySelector(window.__movyTv.PF.raw.search.input),c=window.__movyTv.currentEl(),m=document.getElementById("movy-tv-mic");JSON.stringify({val:i&&i.value,cur:c&&(c.tagName+":"+(c.getAttribute("href")||c.id||"")),mic:!!(m&&m.classList.contains("on"))})' 2>&1 | tail -1; }
$D shell "am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source $S --es movy_voice '$W'" >/dev/null
sleep ${3:-22}; $D logcat -c
node scripts/tv-test/cdp.mjs "window.__movyTv.drawerAction('search')" >/dev/null; sleep 4
NOWAKE=1 bash scripts/tv-test/t.sh key BACK; sleep 2; echo "field focused, keyboard closed:"; st
for n in 1 2 3; do NOWAKE=1 bash scripts/tv-test/t.sh key RIGHT; sleep 0.5; echo "after RIGHT $n:"; R=$(st); echo "$R"; case "$R" in *movy-tv-mic*) break;; esac; done; bash scripts/tv-test/t.sh shot r5-L3a-$S-mic-focus
NOWAKE=1 bash scripts/tv-test/t.sh key OK; sleep 1.5; bash scripts/tv-test/t.sh shot r5-L3a-$S-mic-listening; sleep 10; echo "after mic:"; st; bash scripts/tv-test/t.sh shot r5-L3a-$S-mic-results
$D logcat -d -s MovySearch | tail -4
