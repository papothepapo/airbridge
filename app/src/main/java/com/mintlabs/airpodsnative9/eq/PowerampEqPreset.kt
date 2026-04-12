package com.mintlabs.airpodsnative9.eq

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class PowerampEqBand(
    val index: Int,
    val enabled: Boolean,
    val typeCode: Int,
    val typeLabel: String,
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float,
    val channels: Int,
) {
    fun summary(): String {
        val frequencyLabel = if (frequencyHz >= 1000f) {
            String.format("%.1fkHz", frequencyHz / 1000f)
        } else {
            "${frequencyHz.roundToInt()}Hz"
        }
        val gainLabel = String.format("%+.1fdB", gainDb)
        val qLabel = String.format("Q %.2f", q)
        return "$typeLabel $frequencyLabel $gainLabel $qLabel"
    }
}

data class PowerampEqPreset(
    val name: String,
    val preampDb: Float,
    val bands: List<PowerampEqBand>,
    val importedAtMs: Long = System.currentTimeMillis(),
    val sourceLabel: String? = null,
) {
    fun enabledBands(): List<PowerampEqBand> = bands.filter { it.enabled }

    fun previewBands(maxBands: Int): List<PowerampEqBand> {
        val enabled = enabledBands().sortedBy { it.frequencyHz }
        if (enabled.size <= maxBands) return enabled
        if (maxBands <= 1) return listOf(enabled.first())

        val step = enabled.lastIndex.toFloat() / (maxBands - 1).toFloat()
        return List(maxBands) { index ->
            enabled[(index * step).roundToInt()]
        }
    }

    fun previewSummary(maxBands: Int = 4): String {
        val preview = previewBands(maxBands)
        if (preview.isEmpty()) return "No enabled bands"
        return preview.joinToString(" • ") { "${it.index}. ${it.summary()}" }
    }

    fun toStorageJson(): String {
        val root = JSONObject()
        root.put("name", name)
        root.put("preampDb", preampDb.toDouble())
        root.put("importedAtMs", importedAtMs)
        root.put("sourceLabel", sourceLabel)
        val bandsArray = JSONArray()
        bands.forEach { band ->
            val item = JSONObject()
            item.put("index", band.index)
            item.put("enabled", band.enabled)
            item.put("typeCode", band.typeCode)
            item.put("typeLabel", band.typeLabel)
            item.put("frequencyHz", band.frequencyHz.toDouble())
            item.put("gainDb", band.gainDb.toDouble())
            item.put("q", band.q.toDouble())
            item.put("channels", band.channels)
            bandsArray.put(item)
        }
        root.put("bands", bandsArray)
        return root.toString()
    }

    companion object {
        fun fromStorageJson(raw: String): PowerampEqPreset? {
            return runCatching {
                val root = JSONObject(raw)
                val bands = buildList {
                    val array = root.optJSONArray("bands") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(
                            PowerampEqBand(
                                index = item.optInt("index", index + 1),
                                enabled = item.optBoolean("enabled", true),
                                typeCode = item.optInt("typeCode", -1),
                                typeLabel = item.optString("typeLabel", "PK"),
                                frequencyHz = item.optDouble("frequencyHz", 0.0).toFloat(),
                                gainDb = item.optDouble("gainDb", 0.0).toFloat(),
                                q = item.optDouble("q", 0.7).toFloat(),
                                channels = item.optInt("channels", 0),
                            )
                        )
                    }
                }
                if (bands.isEmpty()) return null
                PowerampEqPreset(
                    name = root.optString("name", "Imported preset"),
                    preampDb = root.optDouble("preampDb", 0.0).toFloat(),
                    bands = bands,
                    importedAtMs = root.optLong("importedAtMs", System.currentTimeMillis()),
                    sourceLabel = root.optString("sourceLabel").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }
}
