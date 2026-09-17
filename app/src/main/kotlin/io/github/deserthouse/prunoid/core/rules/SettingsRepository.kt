package io.github.deserthouse.prunoid.core.rules

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

// 用户偏好（DataStore）：默认引擎 / 备份保留份数。
// 键值即唯一事实源，UI 不另存镜像。
class SettingsRepository(private val context: Context) {

    data class Settings(val defaultEngine: String = "IFW", val backupKeep: Int = 10)

    private val KEY_ENGINE = stringPreferencesKey("default_engine")
    private val KEY_BACKUP_KEEP = intPreferencesKey("backup_keep")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultEngine = p[KEY_ENGINE] ?: "IFW",
            backupKeep = p[KEY_BACKUP_KEEP] ?: 10
        )
    }

    suspend fun setDefaultEngine(v: String) {
        context.dataStore.edit { it[KEY_ENGINE] = v }
    }

    suspend fun setBackupKeep(v: Int) {
        context.dataStore.edit { it[KEY_BACKUP_KEEP] = v.coerceIn(3, 30) }
    }
}
