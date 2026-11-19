package de.dgstudios.iptvstream.core.browser

/** Reine Logik für den Fernbedienungs-Zeiger (testbar ohne Gerät). */
object CursorNav {
    enum class Kind { MOVE, SCROLL, TO_TOOLBAR }

    data class Step(val kind: Kind, val x: Float, val y: Float, val scrollDy: Int = 0)

    /** Schrittweite in Pixeln: wird beim Gedrückthalten schneller. */
    fun stepPx(basePx: Float, repeatCount: Int): Float = basePx * (1f + minOf(repeatCount, 12) * 0.45f)

    /**
     * Ein Druck auf eine Pfeiltaste. Am oberen/unteren Rand wird gescrollt; ganz oben auf einer Seite,
     * die nicht weiter nach oben kann, geht der Fokus in die Adressleiste.
     */
    fun move(
        x: Float, y: Float, dx: Int, dy: Int, step: Float,
        width: Float, height: Float, edge: Float, pageAtTop: Boolean,
    ): Step {
        if (dx != 0) return Step(Kind.MOVE, (x + dx * step).coerceIn(0f, width), y)
        val ny = y + dy * step
        return when {
            dy < 0 && ny < edge -> if (pageAtTop && y <= edge) {
                Step(Kind.TO_TOOLBAR, x, y)
            } else if (pageAtTop) {
                Step(Kind.MOVE, x, ny.coerceAtLeast(0f))
            } else {
                Step(Kind.SCROLL, x, ny.coerceAtLeast(edge), -(height * 0.35f).toInt())
            }
            dy > 0 && ny > height - edge -> Step(Kind.SCROLL, x, ny.coerceAtMost(height - edge), (height * 0.35f).toInt())
            else -> Step(Kind.MOVE, x, ny)
        }
    }
}
