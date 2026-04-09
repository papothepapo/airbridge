package com.mintlabs.airpodsnative9.protocol

import com.mintlabs.airpodsnative9.model.AirPodsStatus

object AirPodsPacketParser {
    private const val APPLE_ID = 76
    private const val MIN_PACKET_LEN = 27

    private val modelNames = mapOf(
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x2720 to "AirPods Pro 3",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)"
    )

    private val colorNames = mapOf(
        0x00 to "White",
        0x01 to "Black",
        0x02 to "Red",
        0x03 to "Blue",
        0x04 to "Pink",
        0x05 to "Gray",
        0x06 to "Silver",
        0x09 to "Space Gray"
    )

    private val connStates = mapOf(
        0x00 to "Disconnected",
        0x04 to "Idle",
        0x05 to "Music",
        0x06 to "Call",
        0x07 to "Ringing",
        0x09 to "Hanging Up",
        0xFF to "Unknown"
    )

    fun parseFromManufacturerData(
        manufacturerId: Int,
        data: ByteArray?,
        address: String,
        rssi: Int,
        nowMs: Long = System.currentTimeMillis()
    ): AirPodsStatus? {
        if (manufacturerId != APPLE_ID) return null
        if (data == null || data.size < MIN_PACKET_LEN) return null
        if (data[0].toInt() != 0x07 || data[1].toInt() != 0x19) return null

        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt() and 0xFF] ?: "Unknown"
        val connectionCode = data[10].toInt() and 0xFF
        val connectionState = connStates[connectionCode] ?: "Unknown ($connectionCode)"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val leftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val rightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0
        val flipped = !primaryLeft

        val leftBatteryNibble = if (flipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (flipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F
        val caseBatteryNibble = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val leftCharging = if (flipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val rightCharging = if (flipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val caseCharging = (flags and 0x04) != 0
        val lidOpen = ((lid shr 3) and 0x01) == 0

        return AirPodsStatus(
            address = address,
            timestampMs = nowMs,
            model = model,
            connectionState = connectionState,
            connectionStateCode = connectionCode,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBatteryNibble),
            leftInEar = leftInEar,
            rightInEar = rightInEar,
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            lidOpen = lidOpen,
            color = color,
            rssi = rssi,
            source = "BLE"
        )
    }

    private fun decodeBattery(nibble: Int): Int? {
        return when (nibble) {
            in 0x0..0x9 -> nibble * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }
    }
}
