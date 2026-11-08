#!/bin/bash
# usage: walk.sh KEY...  -> after each key prints: KEY label x,y fullscreen
Q='JSON.stringify((function(){var e=document.querySelector(".movy-tvf")||document.activeElement;if(!e)return "none";var r=e.getBoundingClientRect();return [(e.getAttribute("aria-label")||e.textContent||e.tagName).trim().replace(/\s+/g," ").slice(0,28),Math.round(r.x)+","+Math.round(r.y),location.pathname.slice(0,22),document.fullscreenElement?"FS":"-"].join(" | ")})())'
for k in "$@"; do ./t.sh key $k; sleep 0.7; r=$(./t.sh eval "$Q" | grep -o 'result: .*' | sed 's/result: //; s/\\"//g; s/^"//; s/"$//'); echo "$k  $r"; done
