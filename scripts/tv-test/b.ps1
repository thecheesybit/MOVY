# Copy scripts from packages/inject to assets/shell
New-Item -ItemType Directory -Force -Path apps/mobile/android/app/src/main/assets/shell | Out-Null
Copy-Item packages/inject/tvnav-pure.js, packages/inject/tvnav.js, packages/inject/tvdrawer.js, packages/inject/tvfs.js, packages/inject/tvinset.js, packages/inject/tvmenu.js, packages/inject/tvshell.js, packages/inject/tvpaint.js, packages/inject/tvgate.js, packages/inject/tvfocusmem.js, packages/inject/tvplayerfx.js, packages/inject/loading.js, packages/inject/navguard.js, packages/inject/scamguard.js apps/mobile/android/app/src/main/assets/shell/ -Force

Copy-Item packages/inject/cosmetic.js apps/mobile/android/app/src/main/assets/adblock/ -Force

$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "C:\Users\ak818\AppData\Local\Android\Sdk"
$orig = Get-Location
Set-Location apps/mobile/android
try {
    ./gradlew.bat assembleTvDebug -q
} finally {
    ./gradlew.bat --stop
    Set-Location $orig
}
& 'C:\Users\ak818\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 install -r apps/mobile/android/app/build/outputs/apk/tv/debug/app-tv-debug.apk
