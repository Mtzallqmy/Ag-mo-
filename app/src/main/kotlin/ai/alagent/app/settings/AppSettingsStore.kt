package ai.alagent.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.alAgentDataStore by preferencesDataStore(name = "al_agent_settings")

data class AppSettings(
    val privacyMode: Boolean = false,
    val preferLocal: Boolean = true,
    val preferredModelId: String? = null,
    val localApiEnabled: Boolean = false
)

class AppSettingsStore(private val context: Context) {
    private object Keys {
        val privacyMode = booleanPreferencesKey("privacy_mode")
        val preferLocal = booleanPreferencesKey("prefer_local")
        val preferredModelId = stringPreferencesKey("preferred_model_id")
        val localApiEnabled = booleanPreferencesKey("local_api_enabled")
    }

    val settings: Flow<AppSettings> = context.alAgentDataStore.data.map { preferences ->
        AppSettings(
            privacyMode = preferences[Keys.privacyMode] ?: false,
            preferLocal = preferences[Keys.preferLocal] ?: true,
            preferredModelId = preferences[Keys.preferredModelId],
            localApiEnabled = preferences[Keys.localApiEnabled] ?: false
        )
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun setPrivacyMode(value: Boolean) { context.alAgentDataStore.edit { it[Keys.privacyMode] = value } }
    suspend fun setPreferLocal(value: Boolean) { context.alAgentDataStore.edit { it[Keys.preferLocal] = value } }
    suspend fun setPreferredModel(id: String?) {
        context.alAgentDataStore.edit { preferences ->
            if (id == null) preferences.remove(Keys.preferredModelId) else preferences[Keys.preferredModelId] = id
        }
    }
    suspend fun setLocalApiEnabled(value: Boolean) { context.alAgentDataStore.edit { it[Keys.localApiEnabled] = value } }
}
