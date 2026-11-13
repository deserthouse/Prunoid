package io.github.deserthouse.prunoid.core.rules

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// 规则订阅：多源并存，每源独立缓存（files/rules/src_<id>.json）。
// 合并策略见 RuleRepository.rebuild；旧版单源缓存（rules/subscription.json）
// 首次访问时迁移为官方源缓存，订阅状态不丢。
class RuleSubscription(context: Context) {

    @Serializable
    data class CacheEntry(val url: String, val fetchedAt: String, val snapshot: RuleSnapshot)

    private val dir = java.io.File(context.filesDir, "rules")
    private val legacyFile = java.io.File(dir, "subscription.json")
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    // 批G1：结构化结果——消息文案由 VM 层资源化组装，core 层不持成品串（i18n 清零）
    data class Result(val ok: Boolean, val entries: Int = 0, val version: String = "", val error: String = "")

    private fun cacheFile(id: String) = java.io.File(dir, "src_$id.json")

    init {
        // 旧单源缓存 → 官方源缓存（一次性迁移；官方缓存已存在则保留新数据）
        val official = cacheFile(SettingsRepository.OFFICIAL_SOURCE.id)
        if (legacyFile.exists() && !official.exists()) {
            runCatching { legacyFile.renameTo(official) }
        }
    }

    /** 批X9（M10）：响应体积有界（20MB——官方快照 ~2MB，超限即拒绝）；缓存原子写
     *  （tmp+rename，坏缓存不覆盖好快照）；失败路径不动既有缓存文件。 */
    private val maxBodyBytes: Long = 20L * 1024 * 1024

    suspend fun fetchTo(id: String, url: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val src = resp.body ?: throw IllegalStateException("empty body")
                // 批X9（M10）：流式限长读取——超 20MB 拒绝（官方快照 ~2MB），防超大响应
                src.byteStream().use { ins ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = ins.read(chunk)
                        if (n < 0) break
                        total += n
                        check(total <= maxBodyBytes) { "response too large (>20MB)" }
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
            }
            val bodyText = String(body, Charsets.UTF_8)
            val snapshot = json.decodeFromString<RuleSnapshot>(bodyText)
            require(snapshot.sdks.isNotEmpty()) { "rule source has no entries" }
            dir.mkdirs()
            val entry = CacheEntry(url, java.time.Instant.now().toString(), snapshot)
            val f = cacheFile(id)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(CacheEntry.serializer(), entry))
            if (!tmp.renameTo(f)) {
                tmp.delete()
                error("rule cache atomic replace failed")
            }
            Result(true, entries = snapshot.sdks.size, version = snapshot.generatedAt)
        }.getOrElse { Result(false, error = it.message ?: "unknown error") }
    }

    fun cached(id: String): CacheEntry? = runCatching {
        val text = cacheFile(id).takeIf { it.exists() }?.readText() ?: return null
        json.decodeFromString<CacheEntry>(text)
    }.getOrNull()

    fun clear(id: String) {
        cacheFile(id).delete()
    }

    /** 汇总全部启用源的订阅规则（并集，交给 RuleRepository 合并） */
    fun loadSubscribed(sources: List<SettingsRepository.SubSource>): List<SdkRule> =
        sources.flatMap { cached(it.id)?.snapshot?.sdks ?: emptyList() }
}
