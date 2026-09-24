package io.github.deserthouse.prunoid.core.rules

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

// 用户偏好与订阅源注册表（DataStore 唯一事实源，UI 不另存镜像）
class SettingsRepository(private val context: Context) {

    data class Settings(
        val defaultEngine: String = "IFW",
        val backupKeep: Int = 10,
        val autoReapply: Boolean = true,
        val easterUnlocked: Boolean = false,
        val easterRambleBurned: Boolean = false,
        // 批N：一级工作方式（root=全能力；audit=只读审计）+ IFW 重应用两档（open=启动对账/realtime=常驻服务）
        val workMode: String = "root",
        val reapplyMode: String = "open",
        val backupEnabled: Boolean = false,
        val guided: Boolean = false,
        val sources: List<SubSource> = listOf(OFFICIAL_SOURCE)
    )

    @Serializable
    data class SubSource(
        val id: String,
        val name: String,
        val url: String,
        val builtin: Boolean = false,
        val lastFetched: String = ""
    )

    companion object {
        const val OFFICIAL_URL =
            "https://raw.githubusercontent.com/deserthouse/Prunoid-Rules/main/rules/snapshot.json"
        val OFFICIAL_SOURCE = SubSource(
            id = "official", name = "官方规则源", url = OFFICIAL_URL, builtin = true
        )
        private val KEY_ENGINE = stringPreferencesKey("default_engine")
        private val KEY_BACKUP_KEEP = intPreferencesKey("backup_keep")
        private val KEY_AUTO_REAPPLY = booleanPreferencesKey("auto_reapply")
        private val KEY_EASTER_UNLOCKED = booleanPreferencesKey("easter_unlocked")
        private val KEY_EASTER_RAMBLE = booleanPreferencesKey("easter_ramble_burned")
        private val KEY_WORK_MODE = stringPreferencesKey("work_mode")
        private val KEY_REAPPLY_MODE = stringPreferencesKey("reapply_mode")
        private val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val KEY_GUIDED = booleanPreferencesKey("guided")
        private val KEY_SOURCES = stringPreferencesKey("sub_sources")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultEngine = p[KEY_ENGINE] ?: "IFW",
            backupKeep = p[KEY_BACKUP_KEEP] ?: 10,
            autoReapply = p[KEY_AUTO_REAPPLY] ?: true,
            easterUnlocked = p[KEY_EASTER_UNLOCKED] ?: false,
            easterRambleBurned = p[KEY_EASTER_RAMBLE] ?: false,
            workMode = p[KEY_WORK_MODE] ?: "root",
            reapplyMode = p[KEY_REAPPLY_MODE] ?: "open",
            backupEnabled = p[KEY_BACKUP_ENABLED] ?: false,
            guided = p[KEY_GUIDED] ?: false,
            sources = p[KEY_SOURCES]?.let {
                runCatching { json.decodeFromString<List<SubSource>>(it) }.getOrNull()
            } ?: listOf(OFFICIAL_SOURCE)
        )
    }

    suspend fun setDefaultEngine(v: String) {
        context.dataStore.edit { it[KEY_ENGINE] = v }
    }

    suspend fun setBackupKeep(v: Int) {
        context.dataStore.edit { it[KEY_BACKUP_KEEP] = v.coerceIn(1, 99999) }
    }

    suspend fun setAutoReapply(v: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_REAPPLY] = v }
    }

    suspend fun setEasterUnlocked(v: Boolean) {
        context.dataStore.edit { it[KEY_EASTER_UNLOCKED] = v }
    }

    suspend fun setEasterRambleBurned(v: Boolean) {
        context.dataStore.edit { it[KEY_EASTER_RAMBLE] = v }
    }

    suspend fun setWorkMode(v: String) {
        context.dataStore.edit { it[KEY_WORK_MODE] = v }
    }

    suspend fun setReapplyMode(v: String) {
        context.dataStore.edit { it[KEY_REAPPLY_MODE] = v }
    }

    suspend fun setBackupEnabled(v: Boolean) {
        context.dataStore.edit { it[KEY_BACKUP_ENABLED] = v }
    }

    suspend fun setGuided() {
        context.dataStore.edit { it[KEY_GUIDED] = true }
    }

    suspend fun setSources(list: List<SubSource>) {
        context.dataStore.edit { it[KEY_SOURCES] = json.encodeToString(list) }
    }

    suspend fun updateSourceFetched(id: String, fetchedAt: String) {
        context.dataStore.edit { p ->
            val cur = p[KEY_SOURCES]?.let {
                runCatching { json.decodeFromString<List<SubSource>>(it) }.getOrNull()
            } ?: listOf(OFFICIAL_SOURCE)
            p[KEY_SOURCES] = json.encodeToString(cur.map { if (it.id == id) it.copy(lastFetched = fetchedAt) else it })
        }
    }
}
