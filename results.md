# Airbridge Debug Results

## Baseline

- Date: 2026-04-10
- Repo: `papothepapo/airbridge`
- Local branch: `main`
- Connected Android device: `0123456789ABCDEF` (`Oilsky_M308`)
- Current user report:
  - ANC mode control works
  - Battery level reporting does not work
  - In-ear detection does not work
  - EQ does not work

## Initial Checks

- Repository cloned successfully.
- `adb` is available on the Linux host.
- Device is visible via `adb devices -l`.
- App package id from Gradle config: `com.mintlabs.airpodsnative9`
- App not yet installed during initial baseline capture.
- Root confirmed on device via `adb shell su -c id`.
- First `assembleDebug` attempt failed because the local Android SDK path was not configured.
- Local SDK found at `/home/mint/android-sdk`; `local.properties` added for build use.

## Next Actions

- Build debug APK.
- Install on connected device.
- Capture baseline logs while reproducing battery, ear-detection, and EQ issues.

## Findings In Progress

- Baseline app build installs and starts successfully on the connected Android 9 device.
- AAP transport is live against `34:0E:22:4C:86:F8` (`yitz’s airpods pro`).
- The live notification and UI both showed:
  - `Battery: -- / -- / --`
  - `Fit: L Out • R Out • Lid Closed`
  - `AAP live`
- Bluetooth HCI snoop capture confirms the device is sending full AAP packets for:
  - battery (`0x0004`)
  - device info (`0x001d`)
  - EQ data (`0x0053`)
  - ear state (`0x0006`)
- Root cause identified for battery/EQ/device-info parsing:
  - `AapFramer` was incorrectly treating bytes `2..3` as a message length.
  - On live traffic that truncated long AAP packets down to 8 bytes, which breaks battery and EQ decode.
- Parser patch applied in `AapClient.kt` to split AAP traffic by observed packet header markers instead of the invalid length assumption.

## Latest Live Validation

- Rebuilt and reinstalled the patched debug APK successfully.
- Verified on-device improvements after the framing fix:
  - model/device info now resolves live instead of staying generic
  - app now shows `AirPods Pro 3`
  - adaptive controls now appear in the UI
  - in-ear state is changing live
- Latest observed UI state after reinstall:
  - `Battery: -- / -- / --`
  - `Fit: L In • R In • Lid Closed`
  - `AAP live`
- Remaining active issues:
  - battery values are still not surfacing in the UI despite live AAP battery packets in the HCI capture
  - automatic ear-detection setting state is still not mapped correctly even though live in-ear status now updates
  - EQ telemetry still needs direct UI verification after the parser fix

## Battery Reporting Fix

- Date: 2026-04-12
- Root cause refined:
  - AAP reads were still being passed through a header-scanning framer that could mis-handle packetized classic L2CAP traffic.
  - Battery packets are valid on the wire, but they are commonly adjacent to large AAP payloads; treating the socket like an arbitrary byte stream was the wrong model here.
- Fix applied in `AapClient.kt`:
  - read buffer raised to `8192` bytes to avoid truncating whole AAP packets on OEM stacks
  - `AapFramer` simplified to parse each L2CAP read as a single AAP packet after trimming any leading noise, instead of trying to split inside the packet by repeated header markers
- Live validation on the connected Android 9 device:
  - rebuilt and reinstalled patched APK successfully
  - restarted the foreground service from the UI
  - foreground notification now shows:
    - `AirPods Pro 3 • Connected • L 100% | R 100% | Case 0% • Transparency • AAP`
  - rechecked after 10 seconds and the notification still showed the same battery values
- Status:
  - battery reporting is now working over AAP
  - next task is importing a Poweramp-compatible parametric EQ profile

## Poweramp EQ Import

- Date: 2026-04-12
- Poweramp import path added:
  - supports Poweramp parametric text presets (`Preamp:` / `Filter N:` lines)
  - supports Poweramp JSON parametric preset files
  - imported presets are normalized, stored in app preferences, and restored after restart
  - EQ card now exposes:
    - `Import Poweramp File`
    - `Paste Poweramp Text`
    - `Clear Imported Preset`
- Validation:
  - parser covered with local unit tests for both text and JSON formats
  - pushed `airbridge_poweramp_sample.json` to `/sdcard/Download/`
  - imported it through the on-device DocumentsUI picker
  - Airbridge UI then showed:
    - `Imported preset: ADB Sample • Preamp -4.5 dB • 4 bands`
    - `Clear Imported Preset`
    - imported band preview plus the live telemetry line
- Notes:
  - this import path is local and lightweight by design
  - it does not add any new background worker, polling loop, or radio activity

## BLE Scan Residency

- Date: 2026-04-12
- Service optimization applied:
  - BLE scanning now stops when AAP enters `connecting`, `handshaking`, or `ready`
  - BLE scanning resumes automatically when AAP falls back to reconnect/search states
- Validation on the Android 9 device while AAP remained live:
  - `dumpsys bluetooth_manager` showed `LE scans (started/stopped) : 18 / 18`
  - rechecked 10 seconds later and the counts were still `18 / 18`
  - notification still showed:
    - `AirPods Pro 3 • Connected • L 100% | R 100% | Case 0% • Transparency • AAP`

## Rollback Request

- User requested rollback to the earlier tested app variant rather than the repo baseline.
- Target rollback state:
  - keep the earlier AAP framing fix that restored working live in-ear reporting
  - drop later experimental battery parser changes that made the current build less usable
- README updated to reflect that in-ear reporting works in the preferred current device build, while battery and EQ remain the open issues.
- Rolled-back build compiled and reinstalled successfully on the connected device.
- Immediate post-install UI capture landed during a fresh `Searching` state rather than an active AirPods session, so the rollback install is confirmed but the final live in-ear recheck still needs the headset to be actively reporting during capture.
