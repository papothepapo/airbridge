# Airbridge Transport Notes

Airbridge uses two separate transport paths:

## 1. Apple BLE manufacturer packets

- Manufacturer id: `0x004C`
- Payload prefix: `07 19`
- Used for:
  - model hints
  - left/right/case battery
  - lid state
  - fit state
  - connection-state hints

These packets are parsed in [`AirPodsPacketParser.kt`](app/src/main/java/com/mintlabs/airpodsnative9/protocol/AirPodsPacketParser.kt).

## 2. Hidden AAP control channel over L2CAP

- PSM: `0x1001`
- Used for:
  - ANC and adaptive listening modes
  - Conversation Awareness
  - Personalized Volume
  - adaptive noise tuning
  - microphone routing
  - volume swipe controls
  - case tone and gesture timing options
  - richer device state and telemetry

The client and message framing live in:

- [`AapClient.kt`](app/src/main/java/com/mintlabs/airpodsnative9/aap/AapClient.kt)
- [`AapControlModels.kt`](app/src/main/java/com/mintlabs/airpodsnative9/aap/AapControlModels.kt)
- [`L2capSocketFactory.kt`](app/src/main/java/com/mintlabs/airpodsnative9/aap/L2capSocketFactory.kt)

## Device compatibility notes

- Android 9 devices often need the legacy BLE scan path.
- Hidden L2CAP socket creation depends on non-SDK Bluetooth APIs.
- Root is used to prepare the Bluetooth stack for a stable AAP session on older ROMs.
- Some devices expose only a generic classic Bluetooth name, so Airbridge includes a manual capability-profile override in the UI.

## What to validate on a new target device

1. BLE packets are visible and parsed.
2. `AAP live` can be reached without repeated session failure.
3. At least one control command succeeds from the UI.
4. The correct feature profile is resolved automatically or pinned manually.
