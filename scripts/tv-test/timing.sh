#!/bin/bash
# usage: timing.sh launch <source> [wait_secs]   force-stop + start the app on <source>, print ms to splash lift / page finished
#        timing.sh switch <source> [wait_secs]   send a source switch to the running app, print ms to page finished
#        timing.sh clear                         delete the asset cache and the WebView HTTP cache (prefs and cookies stay)
#        timing.sh stats [n]                     last n asset-cache / preload log lines
# <source> "-" for launch = plain start (the saved source opens).
# All times come from one logcat clock (-v epoch): the ActivityTaskManager START line is t0.
ADB="/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe"
D="$ADB -s emulator-5554"
PKG=com.ayushkumar.movy.tv

report() {
  # first START line is t0; first "dismissing TV splash" and first "page done" after it are t1/t2
  $D logcat -v epoch -d | awk '
    /ActivityTaskManager.*START u0.*com.ayushkumar.movy.tv/ && !t0 { t0 = $1 }
    /dismissing TV splash/ && t0 && !t1 { t1 = $1 }
    /page done/ && t0 && !t2 { t2 = $1 }
    END {
      if (!t0) { print "no START line"; exit }
      printf "splash_lift_ms=%s page_finished_ms=%s\n", (t1 ? int((t1 - t0) * 1000) : "n/a"), (t2 ? int((t2 - t0) * 1000) : "n/a")
    }'
}

case "$1" in
  clear)
    $D shell am force-stop $PKG
    $D shell run-as $PKG rm -rf cache/movy-assets cache/WebView
    ;;
  launch)
    $D shell am force-stop $PKG
    sleep 1
    $D logcat -c
    if [ "$2" = "-" ]; then $D shell am start -n $PKG/app.movy.shell.MainActivity >/dev/null
    else $D shell am start -n $PKG/app.movy.shell.MainActivity --es movy_source "$2" >/dev/null; fi
    sleep "${3:-25}"
    report
    ;;
  switch)
    $D logcat -c
    $D shell am start -n $PKG/app.movy.shell.MainActivity --es movy_source "$2" >/dev/null
    sleep "${3:-15}"
    report
    ;;
  stats)
    $D logcat -v time -d -s MovyAssetCache MovySourcePreload | tail -${2:-20}
    ;;
esac
