#!/bin/bash
# usage: ps.sh [frame#]  -> one-line player state of the visible page (or of child frame n) after the last key
cd /d/Movy
if [ -n "$1" ]; then OUT=$(timeout 40 node scripts/tv-test/cdpf.mjs "$1" -f scripts/tv-test/pstate.js)
else OUT=$(timeout 40 node scripts/tv-test/cdp.mjs -f scripts/tv-test/pstate.js); fi
echo "$OUT" | node -e "const s=JSON.parse(JSON.parse(require('fs').readFileSync(0,'utf8')));console.log(JSON.stringify({fs:s.fs,menu:s.menu,focus:s.focus,fade:s.fade,paused:s.paused,track:s.track,agg:s.agg}))"
