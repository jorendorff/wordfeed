#!/bin/bash
# Runs on CI with an emulator attached (see .github/workflows/device.yml).
# Installs the app, pokes at it, and saves screenshots and logcat to $1.
set -x
OUT=${1:-device-out}
mkdir -p "$OUT"
PKG=com.jorendorff.wordfeed

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 30
shot 01-launch

# Scroll the page up (a vertical drag in the middle of the screen).
adb shell input swipe 540 1800 560 900 400
sleep 3
shot 02-scrolled

# A slightly diagonal scroll: should still scroll, not switch pages.
adb shell input swipe 540 1800 640 1200 400
sleep 3
shot 03-diagonal

# Record video while swiping to the next source, back, and to it again,
# to catch brief glitches a screenshot would miss. (The first visit to a
# page with new content used to flash blank.)
adb shell screenrecord --time-limit 12 /sdcard/swipe.mp4 &
REC=$!
sleep 1
adb shell input swipe 950 1500 150 1500 300
sleep 3
adb shell input swipe 150 1500 950 1500 300
sleep 3
adb shell input swipe 950 1500 150 1500 300
wait $REC
sleep 1
adb pull /sdcard/swipe.mp4 "$OUT/swipe.mp4"
shot 04-swiped

# Slow swipes forward through several sources, so the tab strip scrolls and
# brings never-seen tabs into view (creating their pages mid-swipe).
adb shell screenrecord --time-limit 20 /sdcard/forward.mp4 &
REC=$!
sleep 1
for i in 1 2 3 4; do
  adb shell input swipe 900 1500 200 1500 1200
  sleep 3
done
wait $REC
sleep 1
adb pull /sdcard/forward.mp4 "$OUT/forward.mp4"
shot 04b-forward

# Tap Refresh (top bar, second icon from the right).
adb shell input tap 930 190
sleep 15
shot 05-refreshed

adb logcat -d > "$OUT/logcat.txt"
grep -E "Wordfeed|chromium|AndroidRuntime|FATAL" "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true
exit 0
