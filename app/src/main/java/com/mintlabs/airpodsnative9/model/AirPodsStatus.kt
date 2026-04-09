package com.mintlabs.airpodsnative9.model

data class AirPodsStatus(
    val address: String,
    val timestampMs: Long,
    val model: String,
    val connectionState: String,
    val connectionStateCode: Int,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val lidOpen: Boolean,
    val color: String,
    val rssi: Int,
    val source: String
) {
    val anyInEar: Boolean
        get() = leftInEar || rightInEar

    val bothOut: Boolean
        get() = !leftInEar && !rightInEar

    fun batterySummary(): String {
        val left = leftBattery?.let { "${it}%" } ?: "--"
        val right = rightBattery?.let { "${it}%" } ?: "--"
        val case = caseBattery?.let { "${it}%" } ?: "--"
        return "L $left | R $right | Case $case"
    }
}
