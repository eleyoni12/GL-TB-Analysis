package app.iptvplayer

import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Theme colors (background, header, cards, text, accent) + app language. Built in code. */
object SettingsDialog {

    private class Preset(val name: String, val bg: Int, val header: Int, val card: Int, val accent: Int, val text: Int)

    private val presets = listOf(
        Preset("Navy & Gold", 0xFF0B1426.toInt(), 0xFF101C36.toInt(), 0xFF13203A.toInt(), 0xFFD4AF37.toInt(), 0xFFF2F4F8.toInt()),
        Preset("Black & Red", 0xFF0A0A0A.toInt(), 0xFF1A0A0A.toInt(), 0xFF1C1C1C.toInt(), 0xFFE50914.toInt(), 0xFFFFFFFF.toInt()),
        Preset("Forest", 0xFF0B1A12.toInt(), 0xFF10261A.toInt(), 0xFF14301F.toInt(), 0xFF4CD97B.toInt(), 0xFFEAF7EE.toInt()),
        Preset("Purple Night", 0xFF120B22.toInt(), 0xFF1B1033.toInt(), 0xFF211640.toInt(), 0xFFB388FF.toInt(), 0xFFF1EAFF.toInt()),
        Preset("Light", 0xFFF4F6FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E5BD8.toInt(), 0xFF111827.toInt())
    )

    private val palette = listOf(
        0xFF000000, 0xFF0B1426, 0xFF1A1A2E, 0xFF16213E, 0xFF0F3460, 0xFF1B4332, 0xFF3C096C, 0xFF3D0C02,
        0xFF2B2D42, 0xFF4A4E69, 0xFF8D99AE, 0xFFFFFFFF, 0xFFF2F4F8, 0xFFD4AF37, 0xFFE50914, 0xFFFF9F1C,
        0xFF2EC4B6, 0xFF4CD97B, 0xFF1E5BD8, 0xFFB388FF, 0xFFFF5D8F, 0xFFFFD60A
    ).map { it.toInt() }

    fun show(act: AppCompatActivity, onApply: () -> Unit) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        var bg = AppTheme.bg
        var header = AppTheme.header
        var card = AppTheme.card
        var accent = AppTheme.accent
        var text = AppTheme.text

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val swatches = HashMap<String, View>()

        fun paintSwatches() {
            swatches["bg"]?.background = AppTheme.rounded(bg, dp(8).toFloat(), 0xFF888888.toInt(), dp(1))
            swatches["header"]?.background = AppTheme.rounded(header, dp(8).toFloat(), 0xFF888888.toInt(), dp(1))
            swatches["card"]?.background = AppTheme.rounded(card, dp(8).toFloat(), 0xFF888888.toInt(), dp(1))
            swatches["accent"]?.background = AppTheme.rounded(accent, dp(8).toFloat(), 0xFF888888.toInt(), dp(1))
            swatches["text"]?.background = AppTheme.rounded(text, dp(8).toFloat(), 0xFF888888.toInt(), dp(1))
        }

        // Presets
        root.addView(TextView(act).apply { setText(R.string.presets) })
        val presetRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in presets) {
            presetRow.addView(Button(act).apply {
                this.text = p.name
                isAllCaps = false
                setOnClickListener {
                    bg = p.bg; header = p.header; card = p.card; accent = p.accent; text = p.text
                    paintSwatches()
                }
            })
        }
        root.addView(HorizontalScrollView(act).apply { addView(presetRow) })

        // Individual colors
        fun colorRow(key: String, labelRes: Int, get: () -> Int, set: (Int) -> Unit) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(act).apply {
                setText(labelRes)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val sw = View(act).apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(32)) }
            swatches[key] = sw
            row.addView(sw)
            row.setOnClickListener {
                pickColor(act, act.getString(labelRes), get()) { c -> set(c); paintSwatches() }
            }
            root.addView(row)
        }
        colorRow("bg", R.string.c_bg, { bg }, { bg = it })
        colorRow("header", R.string.c_header, { header }, { header = it })
        colorRow("card", R.string.c_card, { card }, { card = it })
        colorRow("text", R.string.c_text, { text }, { text = it })
        colorRow("accent", R.string.c_accent, { accent }, { accent = it })
        paintSwatches()

        // Category order for movies & series
        val sortPrefs = act.getSharedPreferences("iptv", android.content.Context.MODE_PRIVATE)
        root.addView(TextView(act).apply { setText(R.string.cat_sort); setPadding(0, dp(12), 0, 0) })
        val sortRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val sortButtons = ArrayList<Button>()
        val sortLabels = listOf(R.string.sort_newest, R.string.sort_server, R.string.sort_az)
        fun paintSort() {
            val cur = sortPrefs.getInt("cat_sort", 0)
            sortButtons.forEachIndexed { i, b -> b.alpha = if (i == cur) 1f else 0.5f }
        }
        sortLabels.forEachIndexed { i, res ->
            sortRow.addView(Button(act).apply {
                setText(res)
                isAllCaps = false
                setOnClickListener {
                    sortPrefs.edit().putInt("cat_sort", i).apply()
                    paintSort()
                }
            }.also { sortButtons.add(it) })
        }
        paintSort()
        root.addView(HorizontalScrollView(act).apply { addView(sortRow) })

        // Optional TMDB key for ratings
        root.addView(TextView(act).apply { setText(R.string.tmdb_key_label); setPadding(0, dp(12), 0, 0) })
        val tmdbBox = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            hint = "TMDB API key"
            setText(sortPrefs.getString("tmdb_key", ""))
        }
        root.addView(tmdbBox)

        // Language
        root.addView(TextView(act).apply { setText(R.string.language); setPadding(0, dp(12), 0, 0) })
        val langRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, tag) in listOf("العربية" to "ar", "English" to "en")) {
            langRow.addView(Button(act).apply {
                this.text = label
                isAllCaps = false
                setOnClickListener {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            })
        }
        root.addView(langRow)

        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.settings_title)
            .setView(ScrollView(act).apply { addView(root) })
            .setPositiveButton(R.string.done) { _, _ ->
                sortPrefs.edit().putString("tmdb_key", tmdbBox.text.toString().trim()).apply()
                AppTheme.save(act, bg, header, card, accent, text)
                onApply()
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                AppTheme.save(act, AppTheme.DEF_BG, AppTheme.DEF_HEADER, AppTheme.DEF_CARD, AppTheme.DEF_ACCENT, AppTheme.DEF_TEXT)
                onApply()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickColor(act: AppCompatActivity, title: String, initial: Int, onPicked: (Int) -> Unit) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var current = initial or 0xFF000000.toInt()
        var updating = false

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val preview = View(act).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)) }
        val hex = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            maxLines = 1
        }
        fun bar(): SeekBar = SeekBar(act).apply { max = 255; keyProgressIncrement = 8 }
        val r = bar(); val g = bar(); val b = bar()

        fun refresh(fromHex: Boolean) {
            updating = true
            preview.background = AppTheme.rounded(current, dp(8).toFloat(), 0xFF888888.toInt(), dp(1))
            r.progress = Color.red(current); g.progress = Color.green(current); b.progress = Color.blue(current)
            if (!fromHex) hex.setText(String.format("#%06X", current and 0xFFFFFF))
            updating = false
        }
        val barListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                current = Color.rgb(r.progress, g.progress, b.progress)
                refresh(false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        r.setOnSeekBarChangeListener(barListener)
        g.setOnSeekBarChangeListener(barListener)
        b.setOnSeekBarChangeListener(barListener)
        hex.doAfterTextChanged {
            if (updating) return@doAfterTextChanged
            val s = it?.toString().orEmpty()
            if (s.length == 7 && s.startsWith("#")) {
                try { current = Color.parseColor(s); refresh(true) } catch (_: Exception) {}
            }
        }

        root.addView(preview)
        root.addView(hex)
        root.addView(TextView(act).apply { text = "R" }); root.addView(r)
        root.addView(TextView(act).apply { text = "G" }); root.addView(g)
        root.addView(TextView(act).apply { text = "B" }); root.addView(b)

        // Quick palette
        var row: LinearLayout? = null
        palette.forEachIndexed { i, c ->
            if (i % 8 == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(row)
            }
            row!!.addView(View(act).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(3), dp(6), dp(3), dp(3)) }
                background = AppTheme.rounded(c, dp(6).toFloat(), 0xFF888888.toInt(), dp(1))
                isFocusable = true
                isClickable = true
                setOnClickListener { current = c; refresh(false) }
            })
        }
        refresh(false)

        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(ScrollView(act).apply { addView(root) })
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(current) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
