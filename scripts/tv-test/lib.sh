#!/bin/bash
# Source this: K <keys...> sends remote keys, P <label> prints the player state, WAITFS [frame#] waits for fullscreen, GO <path> navigates.
cd /d/Movy
K() { bash scripts/tv-test/t.sh key "$@"; }
P() { echo "   $1 => $(bash scripts/tv-test/ps.sh $FR | cut -c1-150)"; }
GO() { timeout 30 node scripts/tv-test/cdp.mjs "location.href=\"$1\"" >/dev/null; }
WAITFS() {
  for i in $(seq 1 25); do
    if bash scripts/tv-test/ps.sh "$1" 2>/dev/null | grep -q '"fs":true'; then echo "   fullscreen after ~$((i*2))s"; return 0; fi
    sleep 2
  done
  echo "   NO fullscreen after 50s"; return 1
}
# KB: BACK exactly as the remote sends it (no wake key before it; t.sh's default Shift would reset the 5 s idle state).
KB() { NOWAKE=1 bash scripts/tv-test/t.sh key BACK; }
# FRAME_WITH <css>: prints the child frame number whose document has the selector.
FRAME_WITH() { for n in 1 2 3; do if timeout 40 node scripts/tv-test/cdpf.mjs $n "!!document.querySelector('$1')" 2>/dev/null | grep -q true; then echo $n; return; fi; done; }
