@echo off
echo Cleaning any stale locks and processes...
taskkill /F /IM qemu-system-x86_64.exe >nul 2>&1
taskkill /F /IM emulator.exe >nul 2>&1
timeout /t 1 /nobreak >nul

if exist "%USERPROFILE%\.android\avd\GoogleTV_1080p.avd\hardware-qemu.ini.lock" rmdir /s /q "%USERPROFILE%\.android\avd\GoogleTV_1080p.avd\hardware-qemu.ini.lock"
if exist "%USERPROFILE%\.android\avd\GoogleTV_1080p.avd\multiinstance.lock" del /f /q "%USERPROFILE%\.android\avd\GoogleTV_1080p.avd\multiinstance.lock"

echo Starting Google TV 1080p Emulator...
"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd GoogleTV_1080p -no-snapshot-load
pause
