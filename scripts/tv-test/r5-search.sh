#!/bin/bash
# usage: r5-search.sh <site> <word> : drawer Search action (what OK on the drawer item runs) -> keyboard -> type -> BACK -> DOWN; prints state after each step.
# Uses adb text input (goes to the proxy EditText) and CDP reads. Screenshots r5-L3a-<site>-{kbd,typed,back,down}.
cd /d/Movy
ADB="/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe"; D="$ADB -s emulator-5554"
S=$1; W=${2:-dune}
st() { node scripts/tv-test/cdp.mjs 'var i=document.querySelector(window.__movyTv.PF.raw.search.input),a=document.activeElement,c=window.__movyTv.currentEl();JSON.stringify({val:i&&i.value,act:a&&a.tagName,cur:c&&(c.tagName+":"+(c.getAttribute("href")||c.id||""))})' 2>&1 | tail -1; $D shell dumpsys input_method | grep -o "mInputShown=[a-z]*" | head -1; }
bash scripts/tv-test/t.sh open $S ${3:-12}
$D logcat -c
node scripts/tv-test/cdp.mjs "window.__movyTv.drawerAction('search')" >/dev/null
sleep 4; echo "after drawer Search:"; st; bash scripts/tv-test/t.sh shot r5-L3a-$S-kbd
$D shell input text "$W"; sleep 5; echo "typed $W:"; st; bash scripts/tv-test/t.sh shot r5-L3a-$S-typed
NOWAKE=1 bash scripts/tv-test/t.sh key BACK; sleep 2; echo "after BACK:"; st; bash scripts/tv-test/t.sh shot r5-L3a-$S-back
NOWAKE=1 bash scripts/tv-test/t.sh key OK; sleep 3; echo "OK on the box again:"; st; NOWAKE=1 bash scripts/tv-test/t.sh key BACK; sleep 2
NOWAKE=1 bash scripts/tv-test/t.sh key DOWN; sleep 1; echo "after DOWN:"; st; bash scripts/tv-test/t.sh shot r5-L3a-$S-down
$D logcat -d -s MovySearch | tail -4
