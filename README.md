# Airbridge

Airbridge is an Android 9+ AirPods companion app for rooted devices. It combines Apple BLE status parsing with the hidden AirPods AAP control channel to expose battery state, wear detection, playback automation, ANC modes, Conversation Awareness, Personalized Volume, adaptive noise tuning, mic routing, volume swipe options, and related device-side toggles.

The current build was validated on an Oilsky M308 running Android 9 with Magisk root and an AirPods Pro-class headset. The app branding is `Airbridge`; the Android package id intentionally remains `com.mintlabs.airpodsnative9` in this branch so existing Magisk root grants and on-device data survive upgrades during development.

## Features
- BLE status parsing for model hints, battery, lid state, fit state, and connection heuristics
- Foreground bridge service with notification status
- Playback automation for pause/resume on in-ear changes
- AAP controls for:
  - ANC off / noise cancellation / transparency / adaptive
  - Conversation Awareness
  - Personalized Volume
  - Adaptive noise blend
  - Microphone routing
  - Noise control with one AirPod
  - Automatic ear detection
  - Volume swipe enablement and swipe length
  - Case tone volume
  - In-case tone enablement
  - Sleep detection
  - Press speed
  - Press-and-hold duration
  - Allow-Off cycle option
- Poweramp-compatible parametric EQ preset import and local preview
- Manual capability profile override for devices that only expose a generic Bluetooth name

## Requirements

- Linux host with Android SDK platform tools
- Android 9 or newer target device
- Root on the target device
  - Magisk is the validated path
  - `su` access is required for the Bluetooth socket patch helper
- Bluetooth and location enabled on the device
- AirPods or compatible Apple/Beats headset paired over classic Bluetooth

## Build

```bash
./gradlew assembleDebug
```

The debug APK will be written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.mintlabs.airpodsnative9 -c android.intent.category.LAUNCHER 1
```

## Current Device Status

On the validated Oilsky M308 + rooted Android 9 test device, the current debug build now works as the main test branch.

- Working on-device:
  - ANC and listening mode controls
  - live in-ear status reporting
  - battery reporting over AAP
  - model detection and AAP live session bring-up
  - Poweramp-compatible parametric EQ preset import and local preview
  - BLE scanning automatically stops once AAP is live and resumes only when reconnect is needed
- Notes:
  - the Poweramp import path is intentionally lightweight; it stores and previews the imported preset inside Airbridge without adding a background DSP engine or extra polling

## Using The Manual Profile Override

If Android reports only a generic Bluetooth device name, Airbridge may initially classify the headset as a less capable profile. Open `Device Tools` and pin the correct capability profile:

- `Auto`: use live model detection
- `AirPods Pro 2 / 3`: enables Conversation Awareness, Adaptive ANC, Personalized Volume, adaptive noise blend, and volume swipe controls
- Other profile chips pin the corresponding control surface

This override is stored in app preferences and the foreground service restarts itself when the profile changes.

## Porting To Another Device

Airbridge is not tied to the Oilsky M308. To port it to another rooted Android device:

1. Verify BLE scanning works on the target Android version.
   - On Android 9 and older, the legacy scan path in `AirPodsForegroundService` is used.
   - On newer builds, the Bluetooth LE scanner path is used.
2. Verify root shell execution from the app.
   - `adb shell su -c id` should succeed on the device.
   - If your root stack uses KernelSU or APatch instead of Magisk, update the root command flow in [`RootBluetoothPatcher.kt`](app/src/main/java/com/mintlabs/airpodsnative9/root/RootBluetoothPatcher.kt).
3. Check hidden Bluetooth socket access.
   - Airbridge depends on hidden L2CAP socket creation in [`L2capSocketFactory.kt`](app/src/main/java/com/mintlabs/airpodsnative9/aap/L2capSocketFactory.kt).
   - Some OEM ROMs block different hidden APIs or require different fallbacks.
4. Re-test the Bluetooth patch helper against the target stack.
   - OEMs move or relabel Bluetooth services, socket behavior, and SELinux rules.
   - If the patch helper fails, keep the service soft-failing while you adapt the device-specific commands.
5. Reconfirm model detection.
   - BLE model ids are mapped in [`AirPodsPacketParser.kt`](app/src/main/java/com/mintlabs/airpodsnative9/protocol/AirPodsPacketParser.kt).
   - AAP capability mapping and manual overrides live in [`AapControlModels.kt`](app/src/main/java/com/mintlabs/airpodsnative9/aap/AapControlModels.kt).
6. Re-test the end-to-end loop on real hardware.
   - Build
   - Install
   - Confirm `AAP live`
   - Toggle at least one live control
   - Confirm status broadcasts still update the activity

If you want a clean package rename on another device, change `namespace` and `applicationId` in [`app/build.gradle.kts`](app/build.gradle.kts) and then grant fresh root permissions for the new package. Keeping the current package id is useful during iterative bring-up because root managers often key approvals per-package.

## Related Notes

- Transport notes and protocol observations: [`REVERSE_ENGINEERING.md`](REVERSE_ENGINEERING.md)
- Public reverse-engineering work that informed this effort is acknowledged in [`NOTICE.md`](NOTICE.md)
