package de.dgstudios.iptvstream.core.data

/**
 * Eigene Reihenfolge und ausgeblendete Kategorien/Sender.
 * Speicherung als Komma-Liste in den Einstellungen (kein Datenbankfeld nötig).
 */
object ListOrder {
    fun encode(ids: List<String>): String = ids.filter { it.isNotBlank() }.joinToString("\u001f")
    fun decode(raw: String?): List<String> =
        raw?.split('\u001f', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Bekannte Einträge in gespeicherter Reihenfolge, neue hinten; ausgeblendete fehlen. */
    fun <T> apply(
        items: List<T>,
        idOf: (T) -> String,
        order: List<String>,
        hidden: Set<String>,
    ): List<T> {
        if (order.isEmpty() && hidden.isEmpty()) return items
        val byId = items.associateBy(idOf)
        val seen = HashSet<String>()
        val out = ArrayList<T>(items.size)
        for (id in order) {
            if (id in hidden || id in seen) continue
            val item = byId[id] ?: continue
            out += item
            seen += id
        }
        for (item in items) {
            val id = idOf(item)
            if (id in seen || id in hidden) continue
            out += item
            seen += id
        }
        return out
    }

    fun move(ids: List<String>, id: String, delta: Int): List<String> {
        val i = ids.indexOf(id)
        if (i < 0) return ids
        val j = (i + delta).coerceIn(0, ids.lastIndex)
        if (i == j) return ids
        val out = ids.toMutableList()
        out.add(j, out.removeAt(i))
        return out
    }

    fun moveTo(ids: List<String>, id: String, index: Int): List<String> {
        val i = ids.indexOf(id)
        if (i < 0 || ids.isEmpty()) return ids
        val j = index.coerceIn(0, ids.lastIndex)
        if (i == j) return ids
        val out = ids.toMutableList()
        out.add(j, out.removeAt(i))
        return out
    }

    fun moveToStart(ids: List<String>, id: String): List<String> = moveTo(ids, id, 0)

    fun moveToEnd(ids: List<String>, id: String): List<String> =
        if (ids.isEmpty()) ids else moveTo(ids, id, ids.lastIndex)

    fun sortBy(ids: List<String>, labelOf: (String) -> String): List<String> =
        ids.sortedBy { labelOf(it).lowercase() }

    /** Schiebt [fromId] an die Stelle von [toId] (wie beim Drag-and-Drop). */
    fun moveByKey(ids: List<String>, fromId: String, toId: String): List<String> {
        if (fromId == toId) return ids
        val to = ids.indexOf(toId)
        if (to < 0) return ids
        return moveTo(ids, fromId, to)
    }

    fun <T> moveByKey(items: List<T>, idOf: (T) -> String, fromId: String, toId: String): List<T> {
        if (fromId == toId) return items
        val i = items.indexOfFirst { idOf(it) == fromId }
        val j = items.indexOfFirst { idOf(it) == toId }
        if (i < 0 || j < 0) return items
        val out = items.toMutableList()
        out.add(j, out.removeAt(i))
        return out
    }

    /** Neue sichtbare Reihenfolge behalten, übrige (z. B. ausgeblendete) IDs hinten anhängen. */
    fun replaceVisible(saved: List<String>, visible: List<String>): List<String> {
        val vis = visible.toSet()
        return visible + saved.filter { it !in vis }
    }

    fun hide(hidden: Set<String>, id: String): Set<String> = hidden + id
    fun show(hidden: Set<String>, id: String): Set<String> = hidden - id
}
