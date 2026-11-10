package app.iptvplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Story, cast, rating and trailer for a movie/series, shown before playing. */
class DetailsActivity : AppCompatActivity() {

    private lateinit var channel: Channel
    private lateinit var playBtn: Button
    private lateinit var restartBtn: Button
    private lateinit var trailerBtn: Button
    private var movie: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.load(this)
        History.init(this)
        Ratings.init(this)
        val ch = Session.selected
        if (ch == null) {
            finish()
            return
        }
        channel = ch
        setContentView(R.layout.activity_details)
        window.statusBarColor = AppTheme.header
        window.navigationBarColor = AppTheme.bg
        findViewById<View>(R.id.d_root).setBackgroundColor(AppTheme.bg)

        val title = findViewById<TextView>(R.id.d_title)
        val rating = findViewById<TextView>(R.id.d_rating)
        val meta = findViewById<TextView>(R.id.d_meta)
        playBtn = findViewById(R.id.d_play)
        restartBtn = findViewById(R.id.d_restart)
        trailerBtn = findViewById(R.id.d_trailer)

        title.setTextColor(AppTheme.accent)
        meta.setTextColor(AppTheme.muted)
        for (id in intArrayOf(R.id.d_plot, R.id.d_cast, R.id.d_director)) {
            findViewById<TextView>(id).setTextColor(AppTheme.text)
        }
        for (id in intArrayOf(R.id.d_plot_label, R.id.d_cast_label)) {
            findViewById<TextView>(id).setTextColor(AppTheme.accent)
        }
        playBtn.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(AppTheme.accent)
            setTextColor(AppTheme.onAccent)
        }
        restartBtn.setTextColor(AppTheme.accent)
        trailerBtn.setTextColor(AppTheme.accent)
        findViewById<ProgressBar>(R.id.d_progress).indeterminateTintList =
            android.content.res.ColorStateList.valueOf(AppTheme.accent)

        title.text = ch.name
        showRating(rating, ch.rating.orEmpty())
        if (ch.rating.isNullOrBlank()) Ratings.get(ch) { showRating(rating, it) }
        loadPoster(ch.logo)

        val isSeries = ch.url.startsWith(Playlist.SERIES_SCHEME)
        if (!isSeries) {
            movie = ch
            setupMoviePlay(ch)
        } else {
            playBtn.visibility = View.GONE
        }

        val src = getSharedPreferences("iptv", Context.MODE_PRIVATE).getString("source_url", null)
        if (src == null) {
            findViewById<View>(R.id.d_progress).visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { Playlist.fetchDetails(src, ch, cacheDir) } }
            findViewById<View>(R.id.d_progress).visibility = View.GONE
            result.onSuccess { bind(it) }.onFailure {
                Toast.makeText(this@DetailsActivity, R.string.no_details, Toast.LENGTH_SHORT).show()
            }
        }
        playBtn.post { if (playBtn.visibility == View.VISIBLE) playBtn.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        movie?.let { setupMoviePlay(it) } // progress may have changed after watching
    }

    private fun showRating(view: TextView, value: String) {
        if (value.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.text = "★ $value"
        view.background = AppTheme.rounded(0xCC000000.toInt(), 12 * resources.displayMetrics.density)
        view.setTextColor(0xFFFFD54F.toInt())
        view.visibility = View.VISIBLE
    }

    private fun loadPoster(url: String?) {
        val iv = findViewById<ImageView>(R.id.d_poster)
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            iv.setImageResource(R.drawable.ic_tv)
            return
        }
        iv.load(url) {
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
            size(360, 540)
            addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        }
    }

    private fun setupMoviePlay(ch: Channel) {
        val prog = History.progress(ch.url)
        val resumable = prog in 1..94
        playBtn.visibility = View.VISIBLE
        playBtn.setText(if (resumable) R.string.resume else R.string.play)
        restartBtn.visibility = if (resumable) View.VISIBLE else View.GONE
        playBtn.setOnClickListener { play(listOf(ch), 0, false) }
        restartBtn.setOnClickListener { play(listOf(ch), 0, true) }
    }

    private fun play(list: List<Channel>, index: Int, restart: Boolean) {
        Session.channels = list
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("index", index)
                .putExtra("restart", restart)
        )
    }

    private fun bind(d: Details) {
        findViewById<TextView>(R.id.d_title).text = d.title
        val shownRating = d.rating.ifBlank { channel.rating.orEmpty() }
        if (shownRating.isNotBlank()) showRating(findViewById(R.id.d_rating), shownRating)
        if (!d.poster.isNullOrBlank() && d.poster != channel.logo) loadPoster(d.poster)

        val meta = listOf(d.release, d.genre, d.duration).filter { it.isNotBlank() }.joinToString("  ·  ")
        findViewById<TextView>(R.id.d_meta).text = meta

        val plot = findViewById<TextView>(R.id.d_plot)
        plot.text = d.plot
        findViewById<View>(R.id.d_plot_label).visibility = if (d.plot.isBlank()) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.d_cast).text = d.cast
        findViewById<View>(R.id.d_cast_label).visibility = if (d.cast.isBlank()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.d_director).apply {
            text = if (d.director.isBlank()) "" else getString(R.string.director_label) + ":  " + d.director
        }

        if (d.trailer.isNotBlank()) {
            trailerBtn.visibility = View.VISIBLE
            trailerBtn.setOnClickListener { openTrailer(d.trailer) }
        }

        if (d.episodes.isNotEmpty()) showEpisodes(d.episodes)
        if (d.plot.isBlank() && d.cast.isBlank() && d.trailer.isBlank() && d.episodes.isEmpty()) {
            Toast.makeText(this, R.string.no_details, Toast.LENGTH_SHORT).show()
        }
    }

    private fun seasonOf(ep: Channel): Int =
        Regex("^S(\\d+)E").find(ep.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Season buttons on top; only the chosen season's episodes are listed below. */
    private fun showEpisodes(eps: List<Channel>) {
        val box = findViewById<LinearLayout>(R.id.d_episodes)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val seasons = eps.map { seasonOf(it) }.distinct().sorted()

        // First button: play the first episode (or the one in progress).
        val inProgress = eps.indexOfFirst { History.progress(it.url) in 1..94 }
        val startIdx = if (inProgress >= 0) inProgress else 0
        playBtn.visibility = View.VISIBLE
        playBtn.setText(if (inProgress >= 0) R.string.resume else R.string.play)
        playBtn.setOnClickListener { play(eps, startIdx, false) }
        playBtn.post { playBtn.requestFocus() }

        var current = seasonOf(eps[startIdx])
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinkedHashMap<Int, TextView>()

        fun render() {
            list.removeAllViews()
            eps.forEachIndexed { i, ep -> if (seasonOf(ep) == current) list.addView(episodeRow(ep, i, eps, d)) }
            for ((s, tv) in chips) {
                tv.isSelected = s == current
                tv.setTextColor(if (s == current) AppTheme.onAccent else AppTheme.text)
            }
        }

        if (seasons.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (s in seasons) {
                val tv = TextView(this).apply {
                    text = getString(R.string.season_label, s)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding((16 * d).toInt(), (9 * d).toInt(), (16 * d).toInt(), (9 * d).toInt())
                    background = AppTheme.chipSelector(d)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), (8 * d).toInt()) }
                    setOnClickListener {
                        current = s
                        render()
                    }
                }
                chips[s] = tv
                row.addView(tv)
            }
            box.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
        box.addView(list)
        render()
    }

    private fun episodeRow(ep: Channel, index: Int, all: List<Channel>, d: Float): TextView {
        val pr = History.progress(ep.url)
        val num = Regex("^S\\d+E(\\d+)").find(ep.name)?.groupValues?.get(1)
        val title = ep.name.substringAfter("  ", ep.name)
        return TextView(this).apply {
            text = (if (num != null) "$num.  " else "") + title + if (pr in 1..99) "   ($pr%)" else ""
            setTextColor(AppTheme.text)
            textSize = 15f
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = AppTheme.cardSelector(d)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt()) }
            setOnClickListener { play(all, index, false) }
        }
    }

    private fun openTrailer(key: String) {
        val id = key.trim()
        val web = if (id.startsWith("http")) id else "https://www.youtube.com/watch?v=$id"
        try {
            if (id.startsWith("http")) throw IllegalStateException()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_trailer_app, Toast.LENGTH_LONG).show()
            }
        }
    }
}
