package io.github.deserthouse.sdkpruner.core.rules

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// M2-R7 规则订阅：拉取版本化规则源（格式 = 快照同 schema）+ 本地缓存
// 合并策略：订阅条目按 id 覆盖内置快照，其余追加；url/fetchedAt 留痕便于溯源
class RuleSubscription(context: Context) {

    @Serializable
    data class CacheEntry(val url: String, val fetchedAt: String, val snapshot: RuleSnapshot)

    private val cacheFile = java.io.File(context.filesDir, "rules/subscription.json")
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(val ok: Boolean, val message: String, val entries: Int = 0)

    suspend fun fetch(url: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                resp.body?.string() ?: throw IllegalStateException("empty body")
            }
            val snapshot = json.decodeFromString<RuleSnapshot>(body)
            require(snapshot.sdks.isNotEmpty()) { "规则源为空" }
            cacheFile.parentFile?.mkdirs()
            val entry = CacheEntry(url, java.time.Instant.now().toString(), snapshot)
            cacheFile.writeText(json.encodeToString(CacheEntry.serializer(), entry))
            Result(true, "订阅成功：${snapshot.sdks.size} 条规则（版本 ${snapshot.generatedAt.ifEmpty { "未知" }}）", snapshot.sdks.size)
        }.getOrElse { Result(false, "订阅失败：${it.message}") }
    }

    fun cached(): CacheEntry? = runCatching {
        val text = cacheFile.takeIf { it.exists() }?.readText() ?: return null
        json.decodeFromString<CacheEntry>(text)
    }.getOrNull()

    fun clear() {
        cacheFile.delete()
    }

    /** 读取订阅规则（失败/缺失返回空），供 RuleRepository 合并 */
    fun loadSubscribed(): List<SdkRule> = cached()?.snapshot?.sdks ?: emptyList()
}
