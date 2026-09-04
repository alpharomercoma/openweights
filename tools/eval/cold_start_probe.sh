#!/bin/sh
# Copyright 2026 The OpenWeights Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Says why a turn was slow, rather than that it was.
#
# The report this exists for was "31 tok/s prefill, 1 tok/s decode" on a phone that does
# 100 and 16, taken after a reboot and a fresh download. Three things produce that shape
# and they need opposite fixes:
#
#   the weights are in zram   -> Swap large, majflt climbing by hundreds of thousands
#   the file cache evicted    -> Swap small, majflt climbing, RSS steady
#   the cores are throttled   -> Swap ~0, majflt ~0, and CPU time tracks wall time
#
# Only the third is a scheduling or thermal problem, and the first two are the ones that
# look identical from inside the app. So this samples the counters that separate them,
# around whatever the phone is doing while it runs, and prints the differences.
#
# Usage:
#   tools/eval/cold_start_probe.sh [seconds]      # default 60
#
# Start it, then send the message you want measured. Nothing here writes to the device or
# touches anything outside the app's own package.

set -eu

PACKAGE="${OW_PACKAGE:-io.github.alpharomercoma.openweights.debug}"
DURATION="${1:-60}"

if ! adb get-state >/dev/null 2>&1; then
    echo "No device. On the phone: Developer options > Wireless debugging, then" >&2
    echo "  adb mdns services   (or: adb pair <ip>:<port> if it has never been paired)" >&2
    exit 1
fi

PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
if [ -z "$PID" ]; then
    echo "$PACKAGE is not running. Open it first." >&2
    exit 1
fi

# Everything below reads only this process and nothing outside the app's own package.
# `run-as` is what makes /proc/<pid> readable without root on a debuggable build. The
# files are pulled raw and parsed here, so the on-device half stays two cats.
sample() {
    adb shell run-as "$PACKAGE" sh -c \
        "cat /proc/$PID/smaps_rollup; echo ---; cat /proc/$PID/stat" 2>/dev/null | tr -d '\r'
}

# smaps_rollup lines read "Rss:  1234 kB". /proc/<pid>/stat is one line whose second
# field is a command name that may itself contain spaces and brackets, so the fields are
# counted from after the last ')': minflt is then the 8th, majflt the 10th, and utime and
# stime the 12th and 13th.
parse() {
    printf '%s\n' "$1" | awk -v want="$2" '
        /^---$/ { instat = 1; next }
        !instat { if ($1 == want ":") { print $2; found = 1; exit } next }
        instat {
            sub(/.*\) /, "")
            if (want == "minflt")    { print $8;        found = 1 }
            if (want == "majflt")    { print $10;       found = 1 }
            if (want == "cpu_ticks") { print $12 + $13; found = 1 }
            exit
        }
        END { if (!found) print "" }
    '
}

field() { parse "$1" "$2"; }

echo "package    $PACKAGE (pid $PID)"
echo "window     ${DURATION}s — send the message now"
echo

BEFORE="$(sample)"
START="$(date +%s)"
sleep "$DURATION"
AFTER="$(sample)"
WALL=$(( $(date +%s) - START ))

printf '%-12s %12s %12s %12s\n' field before after delta
for key in Rss Pss Swap SwapPss minflt majflt cpu_ticks; do
    b="$(field "$BEFORE" "$key")"; a="$(field "$AFTER" "$key")"
    [ -n "$b" ] && [ -n "$a" ] || continue
    printf '%-12s %12s %12s %12s\n' "$key" "$b" "$a" "$((a - b))"
done

# 100 ticks a second is the Android constant; the ratio, not the unit, is the point.
CPU_MS=$(( ($(field "$AFTER" cpu_ticks) - $(field "$BEFORE" cpu_ticks)) * 10 ))
echo
echo "cpu $CPU_MS ms of $((WALL * 1000)) ms wall, across all threads"
echo
echo "Reading it:"
echo "  Swap delta large, majflt in the hundreds of thousands -> weights are in zram."
echo "  Swap flat, majflt large                               -> file pages being re-read."
echo "  Swap flat, majflt flat, cpu ~ wall x threads          -> throttled, not starved."
echo
echo "The engine logs the same counters itself, around each load, warm and turn:"
echo "  adb logcat -d -s OpenWeights | grep -E '^.*(mem|kv):'"
