#!/usr/bin/env bash
# Device end-to-end checks for VoiceStudio VozIA on a booted emulator or phone.
#   device-e2e.sh <dist-dir> <out-dir> <full:true|false>
# full=true also reboots the device and installs an update over the app.
set -uo pipefail
DIST=$1; OUT=$2; FULL=${3:-false}
PKG=io.github.fakesss.vozia
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
APP=$DIST/app-$ABI-debug.apk; [[ -f $APP ]] || APP=$DIST/app-universal-debug.apk
UPDATE=$DIST/update/app-$ABI-debug.apk; [[ -f $UPDATE ]] || UPDATE=$DIST/update/app-universal-debug.apk
mkdir -p "$OUT"
SUMMARY=$OUT/summary.md
failures=0
log() { echo "$*" | tee -a "$SUMMARY"; }
result() { if [[ $2 == 0 ]]; then log "- ✅ $1"; else log "- ❌ $1"; failures=$((failures+1)); fi; }
instrument() { # name class [extra args...]
    local name=$1 class=$2; shift 2
    adb shell am instrument -w -r -e class "$class" "$@" "$RUNNER" > "$OUT/$name.txt" 2>&1
    grep -h "vozia\.[a-z_]*=" "$OUT/$name.txt" | sed 's/^INSTRUMENTATION_STATUS: /    /' | sort -u >> "$SUMMARY"
    if grep -q "^OK (" "$OUT/$name.txt" && ! grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" "$OUT/$name.txt"; then return 0; fi
    sed -n '/There w/,/^INSTRUMENTATION_CODE/p' "$OUT/$name.txt" | head -60 >> "$SUMMARY"; return 1
}
wait_boot() {
    adb wait-for-device
    for _ in $(seq 1 120); do [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break; sleep 2; done
    sleep 10; adb shell input keyevent 82 >/dev/null 2>&1 || true
}

if [[ $FULL == heavy ]]; then
    # Large models: OmniVoice voice cloning and the full phone dub (Whisper, Qwen3, OmniVoice).
    log "## Heavy models · $(adb shell getprop ro.product.model | tr -d '\r') · API $(adb shell getprop ro.build.version.sdk | tr -d '\r') · RAM $(adb shell cat /proc/meminfo | head -1 | tr -s ' ' | tr -d '\r')"
    adb install -r -g "$APP" > "$OUT/install.txt" 2>&1; adb install -r "$DIST/app-debug-androidTest.apk" >> "$OUT/install.txt" 2>&1
    smoke=(-e downloadSmokeModels true -e acceptModelLicencesForSmoke true)
    start=$(date +%s); instrument clone com.voicestudio.mobile.VoziaHeavyTaskTest -e heavyTasks true -e acceptModelLicences true
    result "Cloned-voice task: OmniVoice progress, Piper task finishes alongside it, cancel frees OmniVoice ($(( $(date +%s) - start )) s including downloads)" $?
    start=$(date +%s); instrument dub com.voicestudio.mobile.PhoneDubInferenceTest -e dubInferenceSmoke true "${smoke[@]}"
    result "Phone dub: Whisper → Qwen3 translation to French → OmniVoice, muxed output ($(( $(date +%s) - start )) s)" $?
    adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
    log ""; log "Failures: $failures"; exit $failures
fi

if [[ $FULL == models ]]; then
    # Every downloadable text-to-speech model through the background task, as the Speak screen runs it.
    log "## TTS models · $(adb shell getprop ro.product.model | tr -d '\r') · API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    adb install -r -g "$APP" > "$OUT/install.txt" 2>&1; adb install -r "$DIST/app-debug-androidTest.apk" >> "$OUT/install.txt" 2>&1
    start=$(date +%s); instrument models com.voicestudio.mobile.VoziaModelMatrixTest -e modelMatrix true -e acceptModelLicences true
    result "Every TTS model speaks through a background task ($(( $(date +%s) - start )) s including downloads)" $?
    adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
    grep -E "FATAL EXCEPTION|AndroidRuntime|signal [0-9]+ \(SIG" -A20 "$OUT/logcat.txt" | head -80 > "$OUT/crashes.txt" || true
    log ""; log "Failures: $failures"; exit $failures
fi

log "## Device $(adb shell getprop ro.product.model | tr -d '\r') · Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')) · $ABI · $(adb shell wm size | tr -d '\r' | sed 's/Physical size: //') · $(adb shell wm density | tr -d '\r' | sed 's/Physical density: //') dpi"
log ""
adb install -r -g "$APP" > "$OUT/install.txt" 2>&1; result "1. Install $(basename "$APP")" $?
adb install -r "$DIST/app-debug-androidTest.apk" >> "$OUT/install.txt" 2>&1
adb logcat -c || true
adb shell am start -W -n $PKG/com.voicestudio.mobile.MainActivity > "$OUT/launch.txt" 2>&1
sleep 6
pid=$(adb shell pidof $PKG | tr -d '\r'); adb exec-out screencap -p > "$OUT/00-launch.png"
[[ -n $pid ]] && grep -q "Status: ok" "$OUT/launch.txt"; result "1. Launch (pid $pid, $(grep TotalTime "$OUT/launch.txt" | tr -d '\r'))" $?
adb shell am force-stop $PKG

instrument native com.voicestudio.mobile.LocalInferenceTest#allPinnedNativeLibrariesLoadTogether; result "Native runtimes (sherpa-onnx, OmniVoice, llama.cpp) load on ART" $?
instrument device com.voicestudio.mobile.VoziaDeviceTest -e acceptModelLicences true -e asr "$FULL"
result "2-6, 10. Model download, real speech generation, ASR round trip ($FULL), export to Downloads (WAV + M4A, no overwrite), system voice, cancellation, failure" $?
instrument ui com.voicestudio.mobile.VoziaUiTest; result "4-6. UI in Portuguese: type, Generate, play, Save to Downloads" $?
adb pull "/sdcard/Android/data/$PKG/files/screenshots" "$OUT/screenshots" > /dev/null 2>&1 || true

# 7. The exported files are ordinary public files: another process (adb shell) lists, reads and pulls them.
adb shell content query --uri content://media/external/downloads --projection _display_name:relative_path:_size:mime_type > "$OUT/mediastore.txt" 2>&1
adb shell ls -l "/sdcard/Download/VoiceStudio/Teste\ E2E/" > "$OUT/downloads-ls.txt" 2>&1
mkdir -p "$OUT/exported"; adb pull "/sdcard/Download/VoiceStudio/" "$OUT/exported/" > /dev/null 2>&1
python3 - "$OUT/exported" > "$OUT/outside-app-check.txt" 2>&1 <<'EOF'
import sys, wave, pathlib
root = pathlib.Path(sys.argv[1]); ok = False
for f in sorted(root.rglob('*')):
    if f.suffix == '.wav':
        with wave.open(str(f)) as w:
            seconds = w.getnframes() / w.getframerate()
            print(f"{f.relative_to(root)}: WAV {w.getframerate()} Hz, {w.getnchannels()} ch, {seconds:.2f} s"); ok = ok or seconds > 1
    elif f.suffix == '.m4a':
        head = f.read_bytes()[:12]; print(f"{f.relative_to(root)}: M4A ftyp={head[4:8] == b'ftyp'} {f.stat().st_size} bytes")
sys.exit(0 if ok else 1)
EOF
result "7. Exported files readable outside the app: $(tr '\n' ';' < "$OUT/outside-app-check.txt")" $?

adb shell am force-stop $PKG
instrument persist-restart com.voicestudio.mobile.VoziaPersistenceTest; result "8. Data persists after closing (force-stop) and reopening" $?
adb shell am start -W -n $PKG/com.voicestudio.mobile.MainActivity > /dev/null 2>&1; sleep 4; adb exec-out screencap -p > "$OUT/12-reopened.png"

if [[ $FULL == true ]]; then
    # 10. Low storage: fill /data, leaving about 3 MB, then check every write path; always remove the filler.
    free_kb=$(adb shell df /data | awk 'NR==2 {print $4}' | tr -d '\r')
    fill=$(( free_kb * 1024 - 3 * 1024 * 1024 ))
    adb shell fallocate -l "$fill" /data/local/tmp/vozia-filler > "$OUT/filler.txt" 2>&1
    adb shell df /data >> "$OUT/filler.txt" 2>&1
    instrument low-space com.voicestudio.mobile.VoziaLowSpaceTest -e lowSpace true; code=$?
    adb shell rm -f /data/local/tmp/vozia-filler
    result "10. Low storage refuses downloads, library writes and exports with a clear message" $code
    adb reboot; wait_boot
    instrument persist-reboot com.voicestudio.mobile.VoziaPersistenceTest; result "9. Data persists after rebooting the device" $?
    before=$(adb shell dumpsys package $PKG | grep -m1 versionCode | tr -d '\r' | xargs)
    adb install -r "$UPDATE" > "$OUT/update.txt" 2>&1
    after=$(adb shell dumpsys package $PKG | grep -m1 versionCode | tr -d '\r' | xargs)
    instrument persist-update com.voicestudio.mobile.VoziaPersistenceTest; code=$?
    [[ $before != "$after" ]] || code=1
    result "11. Update ($before → $after) keeps data" $code
fi
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
grep -E "FATAL EXCEPTION|AndroidRuntime" -A20 "$OUT/logcat.txt" | head -80 > "$OUT/crashes.txt" || true
log ""; log "Failures: $failures"
exit $failures
