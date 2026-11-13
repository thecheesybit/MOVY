#!/bin/bash
# usage: play-cinejoy.sh [series-path]  -> on the EMULATOR, opens a Cinejoy series page and presses Resume/Play until the
# fullscreen player is up (the first OK after idle only wakes the screen, so it retries).
cd /d/Movy
P="${1:-/series/2316-the-office-2005}"
node scripts/tv-test/cdp.mjs "location.href='$P'" >/dev/null
sleep 18
for i in 1 2 3; do
  node scripts/tv-test/cdp.mjs 'var e=[...document.querySelectorAll("a,button")].find(x=>/Resume|Play/.test(x.innerText||""));if(e)e.focus();1' >/dev/null
  NOWAKE=1 bash scripts/tv-test/t.sh key OK
  sleep 14
  out=$(node scripts/tv-test/cdp.mjs 'JSON.stringify({u:location.pathname,fs:!!document.fullscreenElement})')
  echo "$out"
  case "$out" in *watch*fs*true*) exit 0;; esac
done
exit 1
