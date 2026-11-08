#!/bin/bash
# Sync inject scripts to generated asset dir
mkdir -p /d/Movy/apps/mobile/android/app/src/main/assets/shell
cp /d/Movy/packages/inject/tvnav-pure.js /d/Movy/packages/inject/tvnav.js /d/Movy/packages/inject/tvfs.js /d/Movy/packages/inject/loading.js /d/Movy/packages/inject/navguard.js /d/Movy/packages/inject/scamguard.js /d/Movy/apps/mobile/android/app/src/main/assets/shell/

cd /d/Movy/apps/mobile/android && export JAVA_HOME="C:\Program Files\Java\jdk-17" ANDROID_HOME="C:\Users\ak818\AppData\Local\Android\Sdk"
mkdir -p /d/Movy/scripts/tv-test
./gradlew.bat assembleTvDebug -q > /d/Movy/scripts/tv-test/gradle-tv.log 2>&1 || { echo BUILD FAILED; grep -E "error:|wrong|FAILED" /d/Movy/scripts/tv-test/gradle-tv.log | head -20; ./gradlew.bat --stop; exit 1; }
./gradlew.bat --stop
"/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 install -r app/build/outputs/apk/tv/debug/app-tv-debug.apk
