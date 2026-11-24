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
data class AppSettings(
    val raw: Map<String, String> = emptyMap(),
    /** Aktive Playlist. Nur damit die Einstellungsseite deren eigene Player-Werte zeigt. */
    val activeProfileId: Long? = null,
) {
    fun get(id: String): String {
        val base = PlaylistOverrides.baseOf(id)
        if (base != null) {
            val pid = activeProfileId ?: return PlaylistOverrides.GLOBAL
            return raw[PlaylistOverrides.storageKey(pid, base)] ?: PlaylistOverrides.GLOBAL
        }
        return raw[id] ?: SettingsSchema.defaultOf(id)
    }

    /**
     * Werte für den Player dieser Playlist: Puffer, Kennung, Live-Format und Decoder dürfen pro
     * Playlist abweichen. „Wie allgemein“ (oder nichts gespeichert) nimmt den allgemeinen Wert.
     */
    fun forProfile(profileId: Long): AppSettings {
        val merged = HashMap(raw)
        for (base in PlaylistOverrides.KEYS) {
            val v = raw[PlaylistOverrides.storageKey(profileId, base)]
            if (!v.isNullOrBlank() && v != PlaylistOverrides.GLOBAL) merged[base] = v
        }
        return AppSettings(merged, profileId)
    }
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
    val compat: String get() = get("compat")
    /** „Video- und Audioverarbeitung“: hw_only, hw, sw, sw_only. */
    val decoder: String get() = get("decoder")
    val resize: String get() = get("resize")
    val reconnect: Boolean get() = on("reconnect")
    val liveFormat: String get() = get("live_format")
    val userAgent: String get() = get("user_agent")
    val seamless: Boolean get() = on("seamless")
    val castEnabled: Boolean get() = on("cast")
    val startMode: String get() = get("start_mode")
    val showNumbers: Boolean get() = on("show_numbers")
    val epgAuto: Boolean get() = on("epg_auto")
    val epgGerman: String get() = get("epg_de")
    /** true: durchsuchbare Schublade. false: bisherige Kategorieleiste. */
    val categoryDrawer: Boolean get() = get("categories") == "drawer"
    /** Fernseher: Rand gegen Overscan ("small", "normal", "more", "max"). */
    val tvMargin: String get() = get("tv_margin")
    /** Fernseher: Bildwiederholrate an das Video anpassen ("off", "seamless", "always"). */
    val afr: String get() = get("afr")
    /** Handy: beim Verlassen der App als kleines Fenster weiterspielen. */
    val pip: Boolean get() = on("pip")
    /** Stream-Infos (Auflösung, FPS, Bitrate, Decoder, Umschaltzeit) im Player einblenden. */
    val streamInfo: Boolean get() = on("stream_info")
}

/** Player-Einstellungen, die eine Playlist (ein Profil) selbst festlegen kann. */
object PlaylistOverrides {
    val KEYS = listOf("buffer", "user_agent", "live_format", "decoder")
    const val GLOBAL = "global"
    private const val PREFIX = "pl_"

    /** Schlüssel in der Einstellungsseite, z. B. "pl_buffer". */
    fun uiId(base: String) = PREFIX + base

    /** Gespeicherter Schlüssel je Playlist, z. B. "pl_7_buffer". */
    fun storageKey(profileId: Long, base: String) = "$PREFIX${profileId}_$base"

    /** "pl_buffer" → "buffer"; alles andere → null. */
    fun baseOf(id: String): String? =
        if (id.startsWith(PREFIX)) id.removePrefix(PREFIX).takeIf { it in KEYS } else null
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
        fun syncRetryKey(profileId: Long) = "sync_retry_$profileId"
        fun catOrderKey(profileId: Long, type: String) = "cat_order_${type}_$profileId"
        fun catHiddenKey(profileId: Long, type: String) = "cat_hidden_${type}_$profileId"
        fun channelOrderKey(profileId: Long, categoryId: String) = "ch_order_${profileId}_$categoryId"
        fun channelHiddenKey(profileId: Long) = "ch_hidden_$profileId"
        fun channelOrderPrefix(profileId: Long) = "ch_order_${profileId}_"
        /** Zuletzt genutztes Nicht-Frei-Profil, damit der Schalter „Meine Sender“ zurückfindet. */
        const val LAST_PROVIDER_PROFILE = "last_provider_profile"

        /** Fernseher: Freie Sender einmalig von Deutsch+Italienisch auf nur Deutsch umgestellt. */
        const val FREE_TV_DE_ONLY = "free_tv_de_only_v1"
    }
}
