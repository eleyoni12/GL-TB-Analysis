package app.iptvplayer

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils

/** User-editable colors, applied from code so they can change at runtime. */
object AppTheme {
    const val DEF_BG = 0xFF0B1426.toInt()
    const val DEF_HEADER = 0xFF101C36.toInt()
    const val DEF_CARD = 0xFF13203A.toInt()
    const val DEF_ACCENT = 0xFFD4AF37.toInt()
    const val DEF_TEXT = 0xFFF2F4F8.toInt()

    var bg = DEF_BG
    var header = DEF_HEADER
    var card = DEF_CARD
    var accent = DEF_ACCENT
    var text = DEF_TEXT

    val muted get() = ColorUtils.setAlphaComponent(text, 170)
    val onAccent get() = if (ColorUtils.calculateLuminance(accent) > 0.5) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
    val stroke get() = ColorUtils.blendARGB(card, text, 0.18f)

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        bg = p.getInt("c_bg", DEF_BG)
        header = p.getInt("c_header", DEF_HEADER)
        card = p.getInt("c_card", DEF_CARD)
        accent = p.getInt("c_accent", DEF_ACCENT)
        text = p.getInt("c_text", DEF_TEXT)
    }

    fun save(ctx: Context, bg: Int, header: Int, card: Int, accent: Int, text: Int) {
        ctx.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
            .putInt("c_bg", bg).putInt("c_header", header).putInt("c_card", card)
            .putInt("c_accent", accent).putInt("c_text", text).apply()
        load(ctx)
    }

    fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokePx: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun headerDrawable(d: Float) = GradientDrawable().apply {
        setColor(header)
        val r = 22 * d
        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    }

    /** Row/card background; shows a thick accent outline when focused (remote control). */
    fun cardSelector(d: Float) = StateListDrawable().apply {
        val r = 14 * d
        val s = (3 * d).toInt()
        addState(intArrayOf(android.R.attr.state_focused), rounded(card, r, accent, s))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(card, r, accent, s))
        addState(intArrayOf(), rounded(card, r))
    }

    fun chipSelector(d: Float) = StateListDrawable().apply {
        val r = 18 * d
        val s = (3 * d).toInt()
        addState(intArrayOf(android.R.attr.state_selected, android.R.attr.state_focused), rounded(accent, r, text, s))
        addState(intArrayOf(android.R.attr.state_focused), rounded(card, r, accent, s))
        addState(intArrayOf(android.R.attr.state_selected), rounded(accent, r))
        addState(intArrayOf(), rounded(card, r, stroke, d.toInt().coerceAtLeast(1)))
    }

    fun searchSelector(d: Float) = StateListDrawable().apply {
        val r = 14 * d
        addState(intArrayOf(android.R.attr.state_focused), rounded(card, r, accent, (3 * d).toInt()))
        addState(intArrayOf(), rounded(card, r, stroke, d.toInt().coerceAtLeast(1)))
    }
}
