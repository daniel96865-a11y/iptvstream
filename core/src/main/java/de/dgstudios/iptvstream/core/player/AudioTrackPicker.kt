package de.dgstudios.iptvstream.core.player

/** Beschreibt eine Audiospur unabhängig von Media3, damit die Auswahl testbar bleibt. */
data class AudioCandidate(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String?,
    val label: String?,
    val channels: Int,
    val mime: String?,
    val supported: Boolean,
    val isDefault: Boolean,
)

object AudioTrackPicker {
    private val aliases: Map<String, List<String>> = mapOf(
        "de" to listOf("de", "deu", "ger", "german", "deutsch"),
        "pl" to listOf("pl", "pol", "polish", "polski"),
        "en" to listOf("en", "eng", "english"),
        "tr" to listOf("tr", "tur", "turkish"),
        "fr" to listOf("fr", "fra", "fre", "french"),
        "es" to listOf("es", "spa", "spanish"),
        "it" to listOf("it", "ita", "italian"),
    )

    fun matchesLanguage(c: AudioCandidate, pref: String): Boolean {
        if (pref == "auto" || pref == "off") return false
        val list = aliases[pref] ?: listOf(pref)
        val lang = c.language?.lowercase()?.substringBefore('-')?.trim()
        if (lang != null && lang.isNotEmpty() && lang in list) return true
        val label = c.label?.lowercase().orEmpty()
        return list.any { it.length > 3 && label.contains(it) }
    }

    private fun codecScore(mime: String?): Int {
        val m = mime?.lowercase().orEmpty()
        return when {
            "mp4a" in m || "aac" in m -> 5
            m.endsWith("ac3") && !m.contains("eac3") -> 4
            "eac3" in m || "e-ac3" in m -> 3
            "mpeg" in m || "mp3" in m -> 2
            else -> 1
        }
    }

    private fun channelScore(c: AudioCandidate, maxChannels: Int): Int =
        if (c.channels in 1..maxChannels) c.channels else 1

    /**
     * Wählt die beste noch nicht gescheiterte Spur: bevorzugte Sprache, dann Default-Flag,
     * dann Kanalzahl (innerhalb des Limits), dann Codec-Zuverlässigkeit.
     */
    fun pick(
        candidates: List<AudioCandidate>,
        preferredLang: String,
        maxChannels: Int,
        failed: Set<String>,
    ): AudioCandidate? {
        val usable = candidates.filter { it.supported && it.id !in failed }
        if (usable.isEmpty()) return null
        return usable.maxWithOrNull(
            compareBy<AudioCandidate>(
                { if (matchesLanguage(it, preferredLang)) 1 else 0 },
                { if (it.isDefault) 1 else 0 },
                { channelScore(it, maxChannels) },
                { codecScore(it.mime) },
                { -it.groupIndex },
            ),
        )
    }
}

/**
 * Begrenzt automatische Audio-Wechsel pro Medium, damit nie eine Endlosschleife entsteht.
 */
class AudioFallbackState(private val maxTrackSwitches: Int = 3) {
    val failed = LinkedHashSet<String>()
    var switches = 0
        private set
    var stereoForced = false
        private set
    var gaveUp = false
        private set

    fun reset() {
        failed.clear()
        switches = 0
        stereoForced = false
        gaveUp = false
    }

    sealed interface Step {
        data class SwitchTo(val track: AudioCandidate) : Step
        data object ForceStereo : Step
        data object GiveUp : Step
    }

    /** Markiert die aktuelle Spur als defekt und liefert den nächsten Schritt. */
    fun onFailure(
        currentId: String?,
        candidates: List<AudioCandidate>,
        preferredLang: String,
        maxChannels: Int,
    ): Step {
        if (gaveUp) return Step.GiveUp
        if (currentId != null) failed.add(currentId)
        if (switches < maxTrackSwitches) {
            val next = AudioTrackPicker.pick(candidates, preferredLang, maxChannels, failed)
            if (next != null) {
                switches++
                return Step.SwitchTo(next)
            }
        }
        if (!stereoForced) {
            stereoForced = true
            // Mit Stereo-Limit dürfen bereits gescheiterte Mehrkanal-Spuren erneut versucht werden.
            failed.clear()
            return Step.ForceStereo
        }
        gaveUp = true
        return Step.GiveUp
    }
}
