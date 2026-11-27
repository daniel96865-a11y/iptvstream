package de.dgstudios.iptvstream.core.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Oberfläche des Browsers ohne WebView (damit sie auch im Screenshot-Test auf der JVM gebaut werden kann):
 * Leiste mit Zurück/Vor/Neu laden, Adressleiste, Beta-Badge, Werbeblocker-Zähler, Menü; Startseite; Zeiger.
 */
class BrowserChrome(private val ctx: Context, val tv: Boolean) {
    private val density = ctx.resources.displayMetrics.density
    private val scale = if (tv) 1.2f else 1f
    fun dp(v: Float): Int = (v * density * scale).toInt()
    fun dp(v: Int): Int = dp(v.toFloat())

    // Hintergrund = Leistenfarbe: so sieht der Bereich hinter Status- und Navigationsleiste ruhig aus.
    val root = FrameLayout(ctx).apply { setBackgroundColor(BAR) }
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val toolbar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BAR)
        // Schwarz-Weiß: auch Symbole/Emoji der Leiste grau (die Webseite darunter bleibt unverändert).
        if (mono) setLayerType(android.view.View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        })
        // Handy: kompakt wie Chrome, eine Zeile ~52 dp (40-dp-Knöpfe + 6 dp oben/unten).
        if (tv) setPadding(dp(8), dp(6), dp(8), dp(6)) else setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    /** Browser schließen, zurück zu IPTV (Handy: ✕, TV: „✕ IPTV“). */
    val close = button(if (tv) "✕  IPTV" else "✕", "Browser schließen, zurück zu IPTV").apply {
        if (tv) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), 0, dp(18), 0)
        }
    }
    val back = button("←", "Zurück")
    val forward = button("→", "Vor")
    val reload = button("⟳", "Neu laden")
    val address = EditText(ctx).apply {
        setSingleLine(true)
        setSelectAllOnFocus(true)
        hint = if (tv) "Suchen oder Adresse eingeben" else "Suche oder Adresse"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextColor(Color.WHITE)
        setHintTextColor(0x99FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 18f else 15f)
        background = focusable(FIELD, dp(22).toFloat())
        if (tv) setPadding(dp(16), dp(8), dp(16), dp(8)) else setPadding(dp(14), dp(6), dp(12), dp(6))
        if (!tv) minHeight = dp(40)
        ellipsize = null
        setHorizontallyScrolling(true)
        isFocusable = true
        isFocusableInTouchMode = true
    }
    val badge = TextView(ctx).apply {
        text = "Beta"
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 13f else 11f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(10).toFloat() }
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
    val blocked = TextView(ctx).apply {
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 14f else 12f)
        contentDescription = "Blockierte Werbung auf dieser Seite"
    }
    val menu = button("⋮", "Browser-Einstellungen")
    val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(BAR)
        visibility = View.INVISIBLE
    }
    val webFrame = FrameLayout(ctx)
    val startTitle = TextView(ctx)
    val startStatus = TextView(ctx)
    val startPage = buildStartPage()
    val cursor = CursorView(ctx).apply { visibility = if (tv) View.VISIBLE else View.GONE }
    val fullscreen = FrameLayout(ctx).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }

    init {
        val gap = dp(6)
        fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
            LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = gap }
        toolbar.addView(close, lp())
        toolbar.addView(back, lp())
        // Handy: Vor und Neu laden stehen im ⋮-Menü, damit die Adressleiste Platz hat.
        if (tv) {
            toolbar.addView(forward, lp())
            toolbar.addView(reload, lp())
        }
        toolbar.addView(address, lp(0, 1f))
        // Am Handy ist die Leiste schmal: Beta steht dort auf der Startseite und im Menü.
        if (tv) toolbar.addView(badge, lp())
        toolbar.addView(blocked, lp())
        toolbar.addView(menu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        webFrame.addView(startPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(webFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(fullscreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setBlocked(0)
    }

    /**
     * Ränder für Status-/Navigationsleiste, Kamera-Aussparung und Tastatur (Android 15 zeichnet randlos).
     * Im Vollbild ([immersive]) nur die Aussparung freihalten.
     */
    var immersive = false
        set(v) {
            field = v
            ViewCompat.requestApplyInsets(root)
        }

    fun applyInsets(insets: WindowInsetsCompat) {
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val bars = if (immersive) cutout else insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        content.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    /** WebView unter Startseite und Zeiger einhängen. */
    fun attachWeb(web: View) {
        attached?.let { if (it !== web) webFrame.removeView(it) }
        (web.parent as? ViewGroup)?.removeView(web)
        webFrame.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        attached = web
        if (tv && cursor.parent == null) webFrame.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        startPage.bringToFront()
        if (tv) cursor.bringToFront()
    }

    private var attached: View? = null

    fun setBlocked(n: Int) {
        blocked.text = "🛡 $n"
    }

    fun setStatus(text: String) {
        startStatus.text = text
    }

    private fun buildStartPage(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            if (tv) setPadding(dp(32), dp(24), dp(32), dp(24)) else setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        startTitle.apply {
            text = "Browser"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 40f else 24f)
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(startTitle)
        row.addView(
            TextView(ctx).apply {
                text = "Beta"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 15f else 11f)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(10).toFloat() }
                setPadding(dp(8), dp(2), dp(8), dp(2))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) },
        )
        col.addView(row)
        fun line(t: String, size: Float, color: Int, top: Int) = TextView(ctx).apply {
            text = t
            gravity = Gravity.CENTER
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) size * 1.15f else size)
        }.also {
            col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        }
        line("Adresse oder Suchbegriff oben eingeben.\nGesucht wird über DuckDuckGo.", if (tv) 15f else 14f, 0xDDFFFFFF.toInt(), if (tv) 14 else 10)
        if (tv) {
            line("Fernbedienung: Pfeiltasten bewegen den Zeiger, OK klickt, am Rand wird gescrollt.\nZurück geht eine Seite zurück, Menü-Taste öffnet die Einstellungen.", 13f, 0x99FFFFFF.toInt(), 18)
        }
        startStatus.apply {
            gravity = Gravity.CENTER
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 15f else 12f)
        }
        col.addView(startStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (tv) 22 else 16) })
        return col
    }

    private fun button(label: String, description: String) = TextView(ctx).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        // Handy: Symbol ~24 dp groß, Tippfläche 40 dp.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 22f else 18f)
        if (tv) {
            minWidth = dp(42)
            minHeight = dp(42)
        } else {
            minWidth = dp(40)
            minHeight = dp(40)
            setPadding(0, 0, 0, 0)
        }
        background = focusable(FIELD, dp(21).toFloat())
        isFocusable = true
        isClickable = true
    }

    /** Hintergrund mit gut sichtbarem Fokusrahmen (Fernbedienung). */
    private fun focusable(fill: Int, radius: Float): StateListDrawable {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, ACCENT)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(FIELD_FOCUS, dp(3)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(FIELD_FOCUS, 0))
            addState(intArrayOf(), shape(fill, 0))
        }
    }

    companion object {
        /** Schwarz-Weiß-Oberfläche (Einstellung „Schwarz-Weiß“); die Webseite selbst bleibt unverändert. */
        @Volatile var mono = false
        val BG get() = if (mono) 0xFF000000.toInt() else 0xFF0B1020.toInt()
        val BAR get() = if (mono) 0xFF141414.toInt() else 0xFF111827.toInt()
        val FIELD get() = if (mono) 0xFF262626.toInt() else 0xFF1F2937.toInt()
        val FIELD_FOCUS get() = if (mono) 0xFF3D3D3D.toInt() else 0xFF334155.toInt()
        val ACCENT get() = if (mono) 0xFF7A7A7A.toInt() else 0xFF14B8A6.toInt()
    }
}

/** Maus-Zeiger über der Webseite (TV). Nach dem DG TV Browser. */
class CursorView(ctx: Context) : View(ctx) {
    var cx = -1f
    var cy = -1f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (BrowserChrome.ACCENT and 0x00FFFFFF) or 0x88000000.toInt(); style = Paint.Style.FILL }
    private var pressed = false

    init {
        isFocusable = false
        isClickable = false
    }

    fun flash() {
        pressed = true
        invalidate()
        postDelayed({ pressed = false; invalidate() }, 150)
    }

    fun moveTo(x: Float, y: Float) {
        cx = x.coerceIn(0f, width.toFloat().coerceAtLeast(1f))
        cy = y.coerceIn(0f, height.toFloat().coerceAtLeast(1f))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (cx < 0) {
            cx = w / 2f
            cy = h / 3f
        }
        cx = cx.coerceIn(0f, w.toFloat())
        cy = cy.coerceIn(0f, h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        if (pressed) c.drawCircle(cx, cy, 22 * d, ring)
        val s = d * 1.6f
        val p = Path().apply {
            moveTo(cx, cy); lineTo(cx, cy + 18 * s); lineTo(cx + 4.5f * s, cy + 13.5f * s)
            lineTo(cx + 8 * s, cy + 21 * s); lineTo(cx + 11 * s, cy + 19.5f * s); lineTo(cx + 7.5f * s, cy + 12.5f * s)
            lineTo(cx + 13 * s, cy + 12.5f * s); close()
        }
        c.drawPath(p, fill)
        c.drawPath(p, stroke)
    }
}
