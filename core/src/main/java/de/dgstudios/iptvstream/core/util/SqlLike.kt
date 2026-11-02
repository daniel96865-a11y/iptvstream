package de.dgstudios.iptvstream.core.util

/** Maskiert \, % und _ für `LIKE ... ESCAPE '\'`, damit die Suche sie wörtlich nimmt. */
object SqlLike {
    fun escape(q: String): String =
        q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
