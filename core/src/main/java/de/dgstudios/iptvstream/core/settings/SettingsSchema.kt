package de.dgstudios.iptvstream.core.settings

data class Option(val id: String, val label: String)

sealed interface SettingDef {
    val id: String
    val title: String
}

/** Auswahl mit festen Optionen. Zwei Optionen "on"/"off" gelten als Schalter. */
data class ChoiceDef(
    override val id: String,
    override val title: String,
    val options: List<Option>,
    val default: String,
    val subtitle: String = "",
) : SettingDef {
    val isToggle: Boolean get() = options.size == 2 && options[0].id == "on" && options[1].id == "off"
    fun labelOf(value: String): String = options.firstOrNull { it.id == value }?.label ?: options.first().label
}

data class ActionDef(
    override val id: String,
    override val title: String,
    val subtitle: String = "",
) : SettingDef

data class InfoDef(
    override val id: String,
    override val title: String,
    val value: String,
) : SettingDef

data class SettingsSection(
    val id: String,
    val title: String,
    val items: List<SettingDef>,
)

object SettingsSchema {
    private val onOff = listOf(Option("on", "Ein"), Option("off", "Aus"))
    private val decoderOptions = listOf(
        Option("hw", "Hardware bevorzugen"),
        Option("sw", "Software bevorzugen"),
        Option("hw_only", "Nur Hardware"),
        Option("sw_only", "Nur Software"),
    )
    private val bufferOptions =
        listOf(Option("small", "Klein (schnell)"), Option("medium", "Mittel"), Option("large", "Groß (stabil)"))
    private val liveFormatOptions = listOf(Option("ts", "MPEG-TS (schnell)"), Option("m3u8", "HLS (kompatibel)"))
    private val userAgentOptions = listOf(
        Option("chrome", "Browser (Standard)"),
        Option("vlc", "VLC"),
        Option("okhttp", "Android-App"),
        Option("smarters", "IPTV Smarters"),
    )
    private val asGlobal = Option(PlaylistOverrides.GLOBAL, "Wie allgemein")

    /** Einstellungen, die nur auf dem Fernseher gelten (das Handy blendet sie aus). */
    val tvOnly = setOf("tv_margin", "afr")

    /** Einstellungen, die nur auf dem Handy gelten (der Fernseher blendet sie aus). */
    val phoneOnly = setOf("pip", "cast")

    val accentNames = listOf("Blau", "Violett", "Türkis", "Grün", "Orange", "Rot", "Violett-Cyan")
    val backgroundNames = listOf("Mitternacht", "Ozean", "Aurora", "Sonnenuntergang", "Graphit", "Glas")

    val sections: List<SettingsSection> = listOf(
        SettingsSection(
            "design", "Design",
            listOf(
                ChoiceDef(
                    "color_theme", "Farbthema",
                    listOf(
                        Option("glas", "Glas & Verlauf"),
                        Option("custom", "Individuell"),
                        Option("blue", "Mitternacht-Blau"),
                        Option("violet", "Royal-Violett"),
                        Option("aqua", "Aqua-Türkis"),
                        Option("green", "Smaragd-Grün"),
                        Option("sunset", "Sonnenuntergang"),
                        Option("coral", "Koralle"),
                        Option("graphite", "Graphit-Rot"),
                    ),
                    "glas",
                    "Abgestimmte Farben für Hintergrund und Glasflächen",
                ),
                ChoiceDef("accent", "Akzentfarbe", accentNames.mapIndexed { i, n -> Option(i.toString(), n) }, "0"),
                ChoiceDef("background", "Hintergrund", backgroundNames.mapIndexed { i, n -> Option(i.toString(), n) }, "0"),
                ChoiceDef(
                    "glass", "Glass-Effekt",
                    listOf(Option("1", "Schwach"), Option("2", "Mittel"), Option("3", "Stark")), "2",
                ),
                ChoiceDef("animations", "Animationen", onOff, "on"),
                ChoiceDef(
                    "theme", "Darstellung",
                    listOf(Option("dark", "Dunkel"), Option("light", "Hell"), Option("system", "System")), "dark",
                ),
                ChoiceDef(
                    "tv_margin", "Bildschirmrand (nur TV)",
                    listOf(
                        Option("small", "Klein (2,5 %)"),
                        Option("normal", "Normal (5 %)"),
                        Option("more", "Größer (7,5 %)"),
                        Option("max", "Maximal (10 %)"),
                    ),
                    "normal",
                    "Ist oben oder rechts etwas abgeschnitten, einen größeren Rand wählen. Viele Fernseher schneiden das Bild an den Rändern ab (Overscan).",
                ),
            ),
        ),
        SettingsSection(
            "clock", "Uhr",
            listOf(
                ChoiceDef("clock", "Uhrzeit anzeigen", onOff, "on"),
                ChoiceDef(
                    "clock_pos", "Position",
                    listOf(
                        Option("tr", "Oben rechts"), Option("tl", "Oben links"),
                        Option("br", "Unten rechts"), Option("bl", "Unten links"),
                    ),
                    "tr",
                ),
                ChoiceDef(
                    "clock_alpha", "Transparenz",
                    listOf(Option("100", "Keine"), Option("70", "Leicht"), Option("50", "Mittel"), Option("30", "Stark")),
                    "70",
                ),
            ),
        ),
        SettingsSection(
            "player", "Player",
            listOf(
                ChoiceDef(
                    "audio_lang", "Audiosprache",
                    listOf(Option("de", "Deutsch"), Option("pl", "Polnisch"), Option("auto", "Original/Automatisch")), "de",
                ),
                ChoiceDef(
                    "sub_lang", "Untertitel",
                    listOf(Option("off", "Aus"), Option("de", "Deutsch"), Option("pl", "Polnisch"), Option("en", "Englisch")),
                    "off",
                ),
                ChoiceDef(
                    "surround", "Tonmodus",
                    listOf(Option("surround", "5.1 Surround (wenn möglich)"), Option("stereo", "Nur Stereo")), "surround",
                ),
                ChoiceDef(
                    "passthrough", "Audio-Passthrough", onOff, "off",
                    "Für stabilen 5.1-Ton ausgeschaltet lassen. Eingeschaltet wird AC3/E-AC3 direkt an den Receiver gegeben.",
                ),
                ChoiceDef(
                    "decoder", "Video- und Audioverarbeitung",
                    decoderOptions,
                    "hw",
                    "Standard: Gerätedecoder zuerst, Software (FFmpeg für AC3/E-AC3/DTS) springt ein. Bleibt ein Sender stumm oder schwarz, „Software bevorzugen“ probieren. Mit Software-Ton wird AC3 nicht an den Receiver durchgereicht. Wirkt ab dem nächsten Sender.",
                ),
                ChoiceDef(
                    "buffer", "Puffergröße",
                    bufferOptions,
                    "medium",
                    "Beim Umschalten startet das Bild immer sofort (unter 1 Sekunde Vorlauf). Hängt ein Sender, baut der Player danach mehr Vorlauf auf: klein bis etwa 15 Sekunden, groß bis zu 90.",
                ),
                ChoiceDef(
                    "resize", "Bildmodus",
                    listOf(Option("fit", "Anpassen"), Option("fill", "Füllen"), Option("stretch", "Strecken"), Option("original", "Original")),
                    "fit",
                ),
                ChoiceDef(
                    "compat", "TV-Kompatibilitätsmodus",
                    listOf(Option("auto", "Automatisch"), Option("on", "Ein"), Option("off", "Aus")),
                    "auto",
                    "Auf Fernsehern automatisch aktiv: Bild höchstens 720p, kleinerer Puffer, bei hängendem Bild automatisch anderer Decoder. Wirkt ab dem nächsten Sender.",
                ),
                ChoiceDef("reconnect", "Automatisch neu verbinden", onOff, "on"),
                ChoiceDef(
                    "live_format", "Live-Format",
                    liveFormatOptions, "ts",
                ),
                ChoiceDef(
                    "seamless", "Nahtlose Neuverbindung (Test)", onOff, "off",
                    "Öffnet bei Streamende sofort eine neue Verbindung im laufenden Player. Kann Bildruckeln auslösen, wenn der Anbieter Daten wiederholt. Standard aus: dann verbindet die Wiederverbindung neu.",
                ),
                ChoiceDef(
                    "user_agent", "Kennung gegenüber dem Anbieter",
                    userAgentOptions,
                    "chrome",
                    "Manche Anbieter behandeln Player unterschiedlich. Wirkt ab dem nächsten Sender.",
                ),
                ChoiceDef(
                    "stream_info", "Stream-Infos anzeigen", onOff, "off",
                    "Auflösung, Bildrate, Bitrate, Decoder und Umschaltzeit oben links im Bild.",
                ),
                ChoiceDef(
                    "afr", "Bildwiederholrate anpassen (nur TV)",
                    listOf(
                        Option("off", "Aus"),
                        Option("seamless", "Nur ohne Bildaussetzer"),
                        Option("always", "Immer (kurz schwarz)"),
                    ),
                    "seamless",
                    "Schaltet den Fernseher auf die Bildrate des Senders (z. B. 50 Hz), gegen Ruckeln bei Kameraschwenks. „Immer“ kann beim Umschalten kurz schwarz werden. Nur wenn Gerät und Fernseher es unterstützen.",
                ),
                ChoiceDef(
                    "pip", "Bild-in-Bild (nur Handy)", onOff, "on",
                    "Beim Verlassen der App läuft der Sender in einem kleinen Fenster weiter.",
                ),
                ChoiceDef("cast", "Chromecast", onOff, "on", "Videos auf Fernseher oder Chromecast senden (nur Handy)"),
            ),
        ),
        SettingsSection(
            "playlist", "Player für diese Playlist",
            listOf(
                InfoDef("playlist_info", "Gilt nur für die aktive Playlist", ""),
                ChoiceDef(PlaylistOverrides.uiId("buffer"), "Puffergröße", listOf(asGlobal) + bufferOptions, PlaylistOverrides.GLOBAL),
                ChoiceDef(PlaylistOverrides.uiId("user_agent"), "Kennung gegenüber dem Anbieter", listOf(asGlobal) + userAgentOptions, PlaylistOverrides.GLOBAL),
                ChoiceDef(PlaylistOverrides.uiId("live_format"), "Live-Format", listOf(asGlobal) + liveFormatOptions, PlaylistOverrides.GLOBAL),
                ChoiceDef(
                    PlaylistOverrides.uiId("decoder"), "Video- und Audioverarbeitung",
                    listOf(asGlobal) + decoderOptions, PlaylistOverrides.GLOBAL,
                    "Wirkt ab dem nächsten Öffnen des Players.",
                ),
            ),
        ),
        SettingsSection(
            "live", "Live TV",
            listOf(
                ChoiceDef(
                    "start_mode", "Beim Start",
                    listOf(
                        Option("list", "Senderliste zeigen"),
                        Option("last", "Zuletzt gesehenen Sender öffnen"),
                        Option("fixed", "Festen Startsender öffnen"),
                    ),
                    "list",
                    "Startsender festlegen: Sender lange drücken",
                ),
                ChoiceDef("show_numbers", "Sendernummern anzeigen", onOff, "on"),
                ChoiceDef(
                    "epg_auto",
                    "EPG automatisch aktualisieren",
                    onOff,
                    "on",
                    "Alle sechs Stunden, auch wenn die App zu ist",
                ),
                ChoiceDef(
                    "epg_de",
                    "Deutscher Programmführer aus dem Netz",
                    listOf(
                        Option("fallback", "Als Ersatz"),
                        Option("prefer", "Bevorzugt"),
                        Option("off", "Aus"),
                    ),
                    "fallback",
                    "Für deutsche Sender; wirkt nach der nächsten EPG-Aktualisierung",
                ),
                ActionDef("epg_refresh", "EPG jetzt aktualisieren"),
            ),
        ),
        SettingsSection(
            "general", "Allgemein",
            listOf(
                ChoiceDef(
                    "categories",
                    "Kategorien",
                    listOf(Option("bar", "Leiste"), Option("drawer", "Schublade")),
                    "bar",
                    "Leiste oder durchsuchbare Schublade, für Live-TV, Filme und Serien",
                ),
                ActionDef("profiles", "Profile verwalten", "Hinzufügen, bearbeiten, löschen"),
                ActionDef("reload", "Inhalte neu laden", "Sender, Filme und Serien aktualisieren"),
                ActionDef(
                    "reset_order",
                    "Eigene Sortierung zurücksetzen",
                    "Kategorien und Sender wieder in der ursprünglichen Reihenfolge",
                ),
                ActionDef("check_update", "Nach Updates suchen"),
                InfoDef("language", "App-Sprache", "Deutsch"),
                InfoDef("version", "Version", "1.0.13"),
            ),
        ),
    )

    private val defaults: Map<String, String> =
        sections.flatMap { it.items }.filterIsInstance<ChoiceDef>().associate { it.id to it.default }

    fun defaultOf(id: String): String = defaults[id] ?: ""
}
