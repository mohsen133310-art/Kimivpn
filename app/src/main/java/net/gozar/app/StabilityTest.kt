package net.gozar.app

import org.json.JSONObject

object StabilityTest {
    enum class Phase { PING, DOWNLOAD, UPLOAD, DONE }
    data class Result(
        val downloadMbps: Double,
        val uploadMbps: Double,
        val idleLatency: Double,
        val jitter: Double,
        val downloadLatency: Double,
        val uploadLatency: Double
    )

    // Full throughput telemetry will be reconnected to sing-box statistics in a later pass.
    suspend fun run(testConfigJson: String, onProgress: (Phase, Double) -> Unit): Result? = null

    private fun parse(json: String): Result? {
        if (json.isBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            if (o.has("error")) null else Result(
                downloadMbps = o.optDouble("download", 0.0),
                uploadMbps = o.optDouble("upload", 0.0),
                idleLatency = o.optDouble("idle", 0.0),
                jitter = o.optDouble("jitter", 0.0),
                downloadLatency = o.optDouble("dlLatency", 0.0),
                uploadLatency = o.optDouble("ulLatency", 0.0)
            )
        }.getOrNull()
    }

    fun toJson(r: Result): String = JSONObject()
        .put("download", r.downloadMbps).put("upload", r.uploadMbps)
        .put("idle", r.idleLatency).put("jitter", r.jitter)
        .put("dlLatency", r.downloadLatency).put("ulLatency", r.uploadLatency)
        .toString()

    fun fromJson(json: String): Result? = parse(json)
}
