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

/**
 * Oberfläche des Browsers ohne WebView (damit sie auch im Screenshot-Test auf der JVM gebaut werden kann):
 * Leiste mit Zurück/Vor/Neu laden, Adressleiste, Beta-Badge, Werbeblocker-Zähler, Menü; Startseite; Zeiger.
 */
class BrowserChrome(private val ctx: Context, val tv: Boolean) {
    private val density = ctx.resources.displayMetrics.density
    private val scale = if (tv) 1.2f else 1f
    fun dp(v: Float): Int = (v * density * scale).toInt()
    fun dp(v: Int): Int = dp(v.toFloat())

    val root = FrameLayout(ctx).apply { setBackgroundColor(BG) }
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val toolbar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BAR)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    val back = button("←", "Zurück")
    val forward = button("→", "Vor")
    val reload = button("⟳", "Neu laden")
    val address = EditText(ctx).apply {
        setSingleLine(true)
        setSelectAllOnFocus(true)
        hint = "Suchen oder Adresse eingeben"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextColor(Color.WHITE)
        setHintTextColor(0x99FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 18f else 15f)
        background = focusable(FIELD, dp(22).toFloat())
        setPadding(dp(16), dp(8), dp(16), dp(8))
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
        toolbar.addView(back, lp())
        toolbar.addView(forward, lp())
        toolbar.addView(reload, lp())
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

    /** WebView unter Startseite und Zeiger einhängen. */
    fun attachWeb(web: View) {
        webFrame.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (tv) webFrame.addView(cursor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

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
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        startTitle.apply {
            text = "Browser"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 40f else 30f)
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(startTitle)
        row.addView(
            TextView(ctx).apply {
                text = "Beta"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 15f else 12f)
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
        line("Adresse oder Suchbegriff oben eingeben.\nGesucht wird über DuckDuckGo.", 15f, 0xDDFFFFFF.toInt(), 14)
        if (tv) {
            line("Fernbedienung: Pfeiltasten bewegen den Zeiger, OK klickt, am Rand wird gescrollt.\nZurück geht eine Seite zurück, Menü-Taste öffnet die Einstellungen.", 13f, 0x99FFFFFF.toInt(), 18)
        }
        startStatus.apply {
            gravity = Gravity.CENTER
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 15f else 13f)
        }
        col.addView(startStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        return col
    }

    private fun button(label: String, description: String) = TextView(ctx).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tv) 22f else 20f)
        minWidth = dp(42)
        minHeight = dp(42)
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
        const val BG = 0xFF0B1020.toInt()
        const val BAR = 0xFF111827.toInt()
        const val FIELD = 0xFF1F2937.toInt()
        const val FIELD_FOCUS = 0xFF334155.toInt()
        const val ACCENT = 0xFF14B8A6.toInt()
    }
}

/** Maus-Zeiger über der Webseite (TV). Nach dem DG TV Browser. */
class CursorView(ctx: Context) : View(ctx) {
    var cx = -1f
    var cy = -1f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8814B8A6.toInt(); style = Paint.Style.FILL }
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
