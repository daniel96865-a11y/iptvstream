package de.dgstudios.iptvstream.core.player

import de.dgstudios.iptvstream.core.data.PlayItem
import de.dgstudios.iptvstream.core.data.PlayKind

/** Stufen für leise Sender. Die Verstärkung liegt über der Geräte-Lautstärke. */
object AudioBoost {
    const val OFF = "off"
    const val LOW = "low"
    const val MID = "mid"
    const val HIGH = "high"

    /** Obergrenze in Millibel. 100 mB entsprechen 1 dB. */
    const val MAX_MILLIBELS = 1_800

    val choices: List<Pair<String, String>> = listOf(
        OFF to "Aus",
        LOW to "Leicht",
        MID to "Stark",
        HIGH to "Sehr stark",
    )

    fun millibels(level: String): Int = when (level) {
        LOW -> 700
        MID -> 1_200
        HIGH -> MAX_MILLIBELS
        else -> 0
    }

    /** Ein Schlüssel pro Sender, Film oder Serie. Folgen einer Serie teilen sich die Stufe. */
    fun key(profileId: Long, item: PlayItem): String {
        val (kind, id) = when (item.kind) {
            PlayKind.EPISODE -> "SERIES" to (item.parentId?.takeIf { it.isNotBlank() } ?: item.id)
            else -> item.kind.name to item.id
        }
        return "audioboost_${profileId}_${kind}_$id"
    }
}
