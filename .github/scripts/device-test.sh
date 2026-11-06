#!/usr/bin/env bash
# Runs the instrumented tests by hand (not via connectedAndroidTest, which
# uninstalls the app and loses its files), then pulls the screenshots.
set -u
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w app.contactscleaner.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
mkdir -p screens
adb pull /sdcard/Android/data/app.contactscleaner/files/screens/. screens/ || true
ls -la screens
grep -q "^OK (" instrument.txt
