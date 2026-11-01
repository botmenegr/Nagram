#!/bin/bash
set -x
APK=$(ls apk/*.apk | head -1)
mkdir -p out
adb wait-for-device
adb root || true
sleep 5
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" > out/install.txt 2>&1; cat out/install.txt
adb logcat -c
adb shell monkey -p xyz.nextalone.nagram -c android.intent.category.LAUNCHER 1
for t in 15 45 90 150; do
  sleep $([ $t = 15 ] && echo 15 || echo 30)
  [ $t = 90 ] && sleep 15
  [ $t = 150 ] && sleep 30
  adb exec-out screencap -p > out/screen_${t}s.png
  adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" > out/activity_${t}s.txt
  adb shell pidof xyz.nextalone.nagram >> out/activity_${t}s.txt
done
adb logcat -d -v threadtime > out/logcat.txt
adb logcat -d -b crash > out/crash.txt
PID=$(adb shell pidof xyz.nextalone.nagram)
if [ -n "$PID" ]; then adb shell kill -3 $PID; sleep 5; fi
adb shell ls -la /data/anr/ > out/anr_ls.txt
adb pull /data/anr out/anr || true
adb shell dumpsys activity processes > out/processes.txt
true
