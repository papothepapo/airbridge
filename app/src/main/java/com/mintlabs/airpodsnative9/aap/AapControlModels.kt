package com.mintlabs.airpodsnative9.aap

enum class AncMode {
    OFF,
    ON,
    TRANSPARENCY,
    ADAPTIVE,
}

enum class MicrophoneMode {
    AUTO,
    ALWAYS_RIGHT,
    ALWAYS_LEFT,
}

enum class PressSpeed {
    DEFAULT,
    SLOWER,
    SLOWEST,
}

enum class PressHoldDuration {
    DEFAULT,
    SHORTER,
    SHORTEST,
}

enum class VolumeSwipeLength {
    DEFAULT,
    LONGER,
    LONGEST,
}

enum class DeviceProfileOverride {
    AUTO,
    AIRPODS_3,
    AIRPODS_PRO,
    AIRPODS_PRO_2_3,
    AIRPODS_4_ANC,
}

data class AirPodsFeatures(
    val hasAncControl: Boolean = false,
    val hasAdaptiveAnc: Boolean = false,
    val hasConversationAwareness: Boolean = false,
    val hasNcOneAirpod: Boolean = false,
    val hasPressSpeed: Boolean = false,
    val hasPressHoldDuration: Boolean = false,
    val hasVolumeSwipe: Boolean = false,
    val hasVolumeSwipeLength: Boolean = false,
    val hasPersonalizedVolume: Boolean = false,
    val hasToneVolume: Boolean = false,
    val hasAdaptiveAudioNoise: Boolean = false,
    val hasMicrophoneMode: Boolean = false,
    val hasEarDetectionToggle: Boolean = false,
    val hasAllowOffOption: Boolean = false,
    val hasSleepDetection: Boolean = false,
    val hasInCaseTone: Boolean = false,
    val hasEqTelemetry: Boolean = false,
    val needsInitExt: Boolean = false,
) {
    val supportedAncModes: List<AncMode>
        get() = when {
            !hasAncControl -> emptyList()
            hasAdaptiveAnc -> listOf(AncMode.OFF, AncMode.ON, AncMode.TRANSPARENCY, AncMode.ADAPTIVE)
            else -> listOf(AncMode.OFF, AncMode.ON, AncMode.TRANSPARENCY)
        }
}

sealed class AapCommand {
    data class SetAncMode(val mode: AncMode) : AapCommand()
    data class SetConversationalAwareness(val enabled: Boolean) : AapCommand()
    data class SetPersonalizedVolume(val enabled: Boolean) : AapCommand()
    data class SetAdaptiveAudioNoise(val level: Int) : AapCommand()
    data class SetMicrophoneMode(val mode: MicrophoneMode) : AapCommand()
    data class SetNcWithOneAirPod(val enabled: Boolean) : AapCommand()
    data class SetEarDetectionEnabled(val enabled: Boolean) : AapCommand()
    data class SetVolumeSwipe(val enabled: Boolean) : AapCommand()
    data class SetVolumeSwipeLength(val value: VolumeSwipeLength) : AapCommand()
    data class SetToneVolume(val level: Int) : AapCommand()
    data class SetInCaseTone(val enabled: Boolean) : AapCommand()
    data class SetSleepDetection(val enabled: Boolean) : AapCommand()
    data class SetPressSpeed(val value: PressSpeed) : AapCommand()
    data class SetPressHoldDuration(val value: PressHoldDuration) : AapCommand()
    data class SetAllowOffOption(val enabled: Boolean) : AapCommand()
}

object AirPodsCatalog {
    data class ModelInfo(
        val modelName: String,
        val features: AirPodsFeatures,
    )

    private val airPods3Features = AirPodsFeatures(
        hasEqTelemetry = true,
    )

    private val airPodsProFeatures = AirPodsFeatures(
        hasAncControl = true,
        hasNcOneAirpod = true,
        hasPressSpeed = true,
        hasPressHoldDuration = true,
        hasToneVolume = true,
        hasMicrophoneMode = true,
        hasEarDetectionToggle = true,
        hasAllowOffOption = true,
        hasEqTelemetry = true,
    )

    private val airPodsPro2Features = AirPodsFeatures(
        hasAncControl = true,
        hasAdaptiveAnc = true,
        hasConversationAwareness = true,
        hasNcOneAirpod = true,
        hasPressSpeed = true,
        hasPressHoldDuration = true,
        hasVolumeSwipe = true,
        hasVolumeSwipeLength = true,
        hasPersonalizedVolume = true,
        hasToneVolume = true,
        hasAdaptiveAudioNoise = true,
        hasMicrophoneMode = true,
        hasEarDetectionToggle = true,
        hasAllowOffOption = true,
        hasSleepDetection = true,
        hasInCaseTone = true,
        hasEqTelemetry = true,
        needsInitExt = true,
    )

    private val modelMap = mapOf(
        "A1523" to ModelInfo("AirPods 1", AirPodsFeatures()),
        "A1722" to ModelInfo("AirPods 1", AirPodsFeatures()),
        "A2031" to ModelInfo("AirPods 2", AirPodsFeatures()),
        "A2032" to ModelInfo("AirPods 2", AirPodsFeatures()),
        "A2564" to ModelInfo("AirPods 3", airPods3Features),
        "A2565" to ModelInfo("AirPods 3", airPods3Features),
        "A2083" to ModelInfo("AirPods Pro", airPodsProFeatures),
        "A2084" to ModelInfo("AirPods Pro", airPodsProFeatures),
        "A2698" to ModelInfo("AirPods Pro 2", airPodsPro2Features),
        "A2699" to ModelInfo("AirPods Pro 2", airPodsPro2Features),
        "A2931" to ModelInfo("AirPods Pro 2", airPodsPro2Features),
        "A3047" to ModelInfo("AirPods Pro 2 (USB-C)", airPodsPro2Features),
        "A3048" to ModelInfo("AirPods Pro 2 (USB-C)", airPodsPro2Features),
        "A3049" to ModelInfo("AirPods Pro 2 (USB-C)", airPodsPro2Features),
        "A3063" to ModelInfo("AirPods Pro 3", airPodsPro2Features),
        "A3064" to ModelInfo("AirPods Pro 3", airPodsPro2Features),
        "A3065" to ModelInfo("AirPods Pro 3", airPodsPro2Features),
        "A3050" to ModelInfo("AirPods 4", AirPodsFeatures(hasEqTelemetry = true)),
        "A3053" to ModelInfo("AirPods 4", AirPodsFeatures(hasEqTelemetry = true)),
        "A3054" to ModelInfo("AirPods 4", AirPodsFeatures(hasEqTelemetry = true)),
        "A3055" to ModelInfo("AirPods 4 (ANC)", airPodsPro2Features),
        "A3056" to ModelInfo("AirPods 4 (ANC)", airPodsPro2Features),
        "A3057" to ModelInfo("AirPods 4 (ANC)", airPodsPro2Features),
    )

    fun resolve(
        modelNumber: String?,
        fallbackName: String?,
        override: DeviceProfileOverride = DeviceProfileOverride.AUTO,
    ): ModelInfo {
        when (override) {
            DeviceProfileOverride.AIRPODS_3 -> return ModelInfo("AirPods 3", airPods3Features)
            DeviceProfileOverride.AIRPODS_PRO -> return ModelInfo("AirPods Pro", airPodsProFeatures)
            DeviceProfileOverride.AIRPODS_PRO_2_3 -> return ModelInfo("AirPods Pro 3", airPodsPro2Features)
            DeviceProfileOverride.AIRPODS_4_ANC -> return ModelInfo("AirPods 4 (ANC)", airPodsPro2Features)
            DeviceProfileOverride.AUTO -> Unit
        }

        val normalized = modelNumber?.trim()?.uppercase().orEmpty()
        modelMap[normalized]?.let { return it }

        val fallback = fallbackName.orEmpty()
        return when {
            fallback.contains("pro 3", ignoreCase = true) -> ModelInfo("AirPods Pro 3", airPodsPro2Features)
            fallback.contains("pro 2", ignoreCase = true) -> ModelInfo("AirPods Pro 2", airPodsPro2Features)
            fallback.contains("airpods pro", ignoreCase = true) -> ModelInfo("AirPods Pro", airPodsProFeatures)
            fallback.contains("airpods 4", ignoreCase = true) -> ModelInfo("AirPods 4", AirPodsFeatures(hasEqTelemetry = true))
            fallback.contains("airpods 3", ignoreCase = true) -> ModelInfo("AirPods 3", airPods3Features)
            fallback.contains("airpods", ignoreCase = true) -> ModelInfo(fallback, airPods3Features)
            else -> ModelInfo(fallback.ifBlank { "AirPods" }, AirPodsFeatures())
        }
    }
}
