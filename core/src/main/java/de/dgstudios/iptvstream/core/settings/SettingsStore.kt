package de.dgstudios.iptvstream.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Alle Werte liegen als Strings im DataStore; Standardwerte kommen aus [SettingsSchema]. */
data class AppSettings(val raw: Map<String, String> = emptyMap()) {
    fun get(id: String): String = raw[id] ?: SettingsSchema.defaultOf(id)
    fun on(id: String): Boolean = get(id) == "on"
    fun int(id: String, def: Int = 0): Int = get(id).toIntOrNull() ?: def
    fun raw(id: String): String? = raw[id]

    val colorTheme: String get() = get("color_theme")
    val accent: Int get() = int("accent")
    val background: Int get() = int("background")
    val glass: Int get() = int("glass", 2)
    val animations: Boolean get() = on("animations")
    val theme: String get() = get("theme")
    val clockOn: Boolean get() = on("clock")
    val clockPos: String get() = get("clock_pos")
    val clockAlpha: Float get() = int("clock_alpha", 70) / 100f
    val audioLang: String get() = get("audio_lang")
    val subLang: String get() = get("sub_lang")
    val surround: Boolean get() = get("surround") == "surround"
    val passthrough: Boolean get() = on("passthrough")
    val buffer: String get() = get("buffer")
    val resize: String get() = get("resize")
    val reconnect: Boolean get() = on("reconnect")
    val liveFormat: String get() = get("live_format")
    val startMode: String get() = get("start_mode")
    val showNumbers: Boolean get() = on("show_numbers")
    val epgAuto: Boolean get() = on("epg_auto")
    /** true: durchsuchbare Schublade. false: bisherige Kategorieleiste. */
    val categoryDrawer: Boolean get() = get("categories") == "drawer"
}

class SettingsStore(private val context: Context) {

    val flow: Flow<AppSettings> = context.settingsDataStore.data
        .map { prefs ->
            val m = HashMap<String, String>()
            for ((k, v) in prefs.asMap()) m[k.name] = v.toString()
            AppSettings(m)
        }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = flow.first()

    suspend fun set(key: String, value: String) {
        context.settingsDataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun remove(key: String) {
        context.settingsDataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun getRaw(key: String): String? = current().raw[key]

    companion object {
        const val ACTIVE_PROFILE = "active_profile"
        fun lastChannelKey(profileId: Long) = "last_channel_$profileId"
        fun fixedChannelKey(profileId: Long) = "fixed_channel_$profileId"
        fun listPosKey(profileId: Long, section: String) = "listpos_${section}_$profileId"
    }
}
