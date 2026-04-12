package com.mintlabs.airpodsnative9.eq

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object PowerampEqPresetParser {
    private data class FilterDescriptor(
        val code: Int,
        val label: String,
    )

    private val preampPattern = Regex(
        pattern = """^\s*Preamp\s*:\s*([-+]?\d*\.?\d+)\s*dB\s*$""",
        option = RegexOption.IGNORE_CASE
    )

    private val filterPattern = Regex(
        pattern = """^\s*Filter\s+(\d+)\s*:\s*(ON|OFF)\s+([A-Za-z]+)(?:\s+Fc\s+([-+]?\d*\.?\d+)\s*Hz\s+Gain\s+([-+]?\d*\.?\d+)\s*dB\s+Q\s+([-+]?\d*\.?\d+))?\s*$""",
        option = RegexOption.IGNORE_CASE
    )

    private val typeDescriptors = mapOf(
        "LS" to FilterDescriptor(0, "LS"),
        "LSC" to FilterDescriptor(0, "LS"),
        "HS" to FilterDescriptor(1, "HS"),
        "HSC" to FilterDescriptor(1, "HS"),
        "PK" to FilterDescriptor(2, "PK"),
        "PEAK" to FilterDescriptor(2, "PK"),
        "PEQ" to FilterDescriptor(2, "PK"),
        "LP" to FilterDescriptor(3, "LP"),
        "HP" to FilterDescriptor(4, "HP"),
        "BP" to FilterDescriptor(5, "BP"),
        "NOTCH" to FilterDescriptor(6, "NOTCH"),
        "NO" to FilterDescriptor(6, "NOTCH"),
        "AP" to FilterDescriptor(7, "AP"),
    )

    fun parse(
        raw: String,
        fallbackName: String? = null,
        sourceLabel: String? = null,
    ): PowerampEqPreset {
        val normalized = raw.trimStart('\uFEFF').trim()
        require(normalized.isNotEmpty()) { "The preset file is empty." }
        return if (normalized.startsWith("{") || normalized.startsWith("[")) {
            parseJson(normalized, fallbackName, sourceLabel)
        } else {
            parseText(normalized, fallbackName, sourceLabel)
        }
    }

    private fun parseJson(
        raw: String,
        fallbackName: String?,
        sourceLabel: String?,
    ): PowerampEqPreset {
        val root = when {
            raw.startsWith("[") -> {
                val array = JSONArray(raw)
                require(array.length() > 0) { "The JSON preset list is empty." }
                array.optJSONObject(0) ?: throw IllegalArgumentException("The JSON preset list did not contain an object.")
            }
            else -> JSONObject(raw)
        }

        if (root.has("parametric")) {
            require(root.optBoolean("parametric", false)) { "The imported JSON preset is not marked as parametric." }
        }

        val bandsArray = root.optJSONArray("bands")
            ?: throw IllegalArgumentException("The JSON preset did not contain any bands.")

        val bands = buildList {
            for (index in 0 until bandsArray.length()) {
                val item = bandsArray.optJSONObject(index) ?: continue
                val frequency = item.optDouble("frequency", Double.NaN).toFloat()
                if (!frequency.isFinite() || frequency <= 0f) continue
                val typeCode = item.optInt("type", -1)
                add(
                    PowerampEqBand(
                        index = index + 1,
                        enabled = item.optBoolean("enabled", true),
                        typeCode = typeCode,
                        typeLabel = typeLabelForCode(typeCode),
                        frequencyHz = frequency,
                        gainDb = item.optDouble("gain", 0.0).toFloat(),
                        q = item.optDouble("q", 0.7).toFloat(),
                        channels = item.optInt("channels", 0),
                    )
                )
            }
        }

        require(bands.isNotEmpty()) { "The JSON preset did not contain any usable EQ bands." }

        return PowerampEqPreset(
            name = root.optString("name").ifBlank { fallbackName ?: "Imported preset" },
            preampDb = root.optDouble("preamp", 0.0).toFloat(),
            bands = bands,
            sourceLabel = sourceLabel,
        )
    }

    private fun parseText(
        raw: String,
        fallbackName: String?,
        sourceLabel: String?,
    ): PowerampEqPreset {
        var preampDb = 0f
        val bands = mutableListOf<PowerampEqBand>()

        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (line.startsWith("GraphicEQ:", ignoreCase = true)) {
                    throw IllegalArgumentException("GraphicEQ presets are not parametric.")
                }

                preampPattern.matchEntire(line)?.let { match ->
                    preampDb = match.groupValues[1].toFloat()
                    return@forEach
                }

                val match = filterPattern.matchEntire(line) ?: return@forEach
                val filterIndex = match.groupValues[1].toInt()
                val enabled = match.groupValues[2].equals("ON", ignoreCase = true)
                val rawType = match.groupValues[3].uppercase(Locale.US)
                if (rawType == "NONE") return@forEach

                val descriptor = typeDescriptors[rawType] ?: FilterDescriptor(-1, rawType)
                val frequencyRaw = match.groupValues[4]
                require(frequencyRaw.isNotBlank()) { "Filter $filterIndex is missing its centre frequency." }

                bands += PowerampEqBand(
                    index = filterIndex,
                    enabled = enabled,
                    typeCode = descriptor.code,
                    typeLabel = descriptor.label,
                    frequencyHz = frequencyRaw.toFloat(),
                    gainDb = match.groupValues[5].ifBlank { "0" }.toFloat(),
                    q = match.groupValues[6].ifBlank { "0.7" }.toFloat(),
                    channels = 0,
                )
            }

        require(bands.isNotEmpty()) { "No Poweramp-style parametric filters were found in the text preset." }

        return PowerampEqPreset(
            name = fallbackName ?: "Imported preset",
            preampDb = preampDb,
            bands = bands.sortedBy { it.index },
            sourceLabel = sourceLabel,
        )
    }

    private fun typeLabelForCode(typeCode: Int): String = when (typeCode) {
        0 -> "LS"
        1 -> "HS"
        2 -> "PK"
        3 -> "LP"
        4 -> "HP"
        5 -> "BP"
        6 -> "NOTCH"
        7 -> "AP"
        else -> "TYPE_$typeCode"
    }
}
