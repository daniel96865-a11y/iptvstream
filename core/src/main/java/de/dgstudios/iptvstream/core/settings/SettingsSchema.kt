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

    val accentNames = listOf("Blau", "Violett", "Türkis", "Grün", "Orange", "Rot")
    val backgroundNames = listOf("Mitternacht", "Ozean", "Aurora", "Sonnenuntergang", "Graphit")

    val sections: List<SettingsSection> = listOf(
        SettingsSection(
            "design", "Design",
            listOf(
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
                    listOf(Option("surround", "Surround (wenn möglich)"), Option("stereo", "Nur Stereo")), "surround",
                ),
                ChoiceDef("passthrough", "Audio-Passthrough", onOff, "off", "AC3/E-AC3 unverändert an den Receiver"),
                ChoiceDef(
                    "buffer", "Puffergröße",
                    listOf(Option("small", "Klein (schnell)"), Option("medium", "Mittel"), Option("large", "Groß (stabil)")),
                    "medium",
                ),
                ChoiceDef(
                    "resize", "Bildmodus",
                    listOf(Option("fit", "Anpassen"), Option("fill", "Füllen"), Option("stretch", "Strecken"), Option("original", "Original")),
                    "fit",
                ),
                ChoiceDef("reconnect", "Automatisch neu verbinden", onOff, "on"),
                ChoiceDef(
                    "live_format", "Live-Format",
                    listOf(Option("ts", "MPEG-TS (schnell)"), Option("m3u8", "HLS (kompatibel)")), "ts",
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
                ChoiceDef("epg_auto", "EPG automatisch aktualisieren", onOff, "on"),
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
                ActionDef("check_update", "Nach Updates suchen"),
                InfoDef("language", "App-Sprache", "Deutsch"),
                InfoDef("version", "Version", "1.0.8"),
            ),
        ),
    )

    private val defaults: Map<String, String> =
        sections.flatMap { it.items }.filterIsInstance<ChoiceDef>().associate { it.id to it.default }

    fun defaultOf(id: String): String = defaults[id] ?: ""
}
