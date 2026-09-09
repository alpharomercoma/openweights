---
name: phone-deploy
description: Use when a build has to reach the project's test phone (POCO X8 Pro Max over wireless debugging) or an instrumented test has to run on it. Covers finding the phone when adb cannot, the install path around the ROM's block on adb install, and running one test class.
---

# Deploy to the test phone

The phone is a POCO X8 Pro Max, HyperOS on Android 16, on wireless debugging. Two things
about it cost time every session: the port changes whenever the pairing screen is opened,
and the ROM refuses `adb install` (INSTALL_FAILED_USER_RESTRICTED) and Gradle's
`connectedAndroidTest` with it. Everything below is the proven path.

## 1. Find the phone

Try adb's own discovery first. It often returns nothing on a cold daemon:

```sh
adb kill-server && adb start-server && sleep 3 && adb mdns services
```

When that stays empty, macOS's own resolver finds it. Each `dns-sd` call runs forever, so
background it and kill it:

```sh
dns-sd -B _adb-tls-connect._tcp local. > /tmp/adb-browse.txt & sleep 4; pkill dns-sd
cat /tmp/adb-browse.txt            # instance name, e.g. adb-EIHAIRFYORFADMCE-PreFuw
dns-sd -L "<instance>" _adb-tls-connect._tcp local. > /tmp/adb-lookup.txt & sleep 4; pkill dns-sd
cat /tmp/adb-lookup.txt            # host and port, e.g. Android-2.local.:36749
dns-sd -G v4 Android-2.local > /tmp/adb-ip.txt & sleep 4; pkill dns-sd
adb connect <ip>:<port>
```

If the browse is empty too, the phone has stopped advertising. HyperOS only advertises
while the Wireless debugging screen is open, so ask for that screen to be opened. Do not
port-scan the subnet; a phone that drops packets silently makes `nc` hang for minutes.

The IP also moves when the Mac is tethered to the phone's hotspot; then the phone is the
default gateway (`route -n get default | grep gateway`).

## 2. Build, one Gradle at a time

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :app:assembleDebug                    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebugAndroidTest         # only when a device test is wanted
```

## 3. Install by push, not by adb install

```sh
SER=<ip>:<port>
adb -s $SER push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app.apk
adb -s $SER shell pm install -r -t --user 0 /data/local/tmp/app.apk
```

The debug package is `io.github.alpharomercoma.openweights.debug`. Push and install the
androidTest APK the same way when a device test is wanted.

## 4. Run one instrumented test class

```sh
adb -s $SER shell am instrument -w -r -e class <fully.qualified.TestClass> \
  io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The engine's device tests skip themselves when no model file is present. Prefer an
instrumented test over driving the UI with `adb shell input`; the phone is usually locked.

## Measuring on this phone

Screen-off runs are throttled by the ROM, two to five times slower with wild variance.
Wake, unlock and cool the phone before timing anything, and say in the write-up that the
screen was on.
