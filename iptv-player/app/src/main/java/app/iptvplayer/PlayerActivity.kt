package app.iptvplayer

import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var pictureBtn: View
    private lateinit var infoBox: View
    private lateinit var epgView: TextView
    private lateinit var epBox: View
    private lateinit var nextBtn: View
    private var controllerVisible = false
    private var nearEnd = false
    private var lastRecoverAt = 0L
    private var lastErrorAt = 0L
    private lateinit var prefs: SharedPreferences
    private val stage = HashMap<Int, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            checkNearEnd()
            val p = player
            if (p != null && p.isPlaying && SystemClock.elapsedRealtime() - lastErrorAt > 20_000) stage.clear()
            handler.postDelayed(this, 1_000)
        }
    }
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        History.init(this)
        AppTheme.load(this)
        prefs = getSharedPreferences("iptv", MODE_PRIVATE)

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.title)
        pictureBtn = findViewById(R.id.btn_picture)
        infoBox = findViewById(R.id.info_box)
        epgView = findViewById(R.id.epg)
        epBox = findViewById(R.id.ep_box)
        nextBtn = findViewById(R.id.btn_next_ep)
        findViewById<View>(R.id.btn_all_eps).setOnClickListener { showAllEpisodes() }
        nextBtn.setOnClickListener { player?.let { it.seekToNextMediaItem(); it.play() } }
        titleView.setTextColor(AppTheme.accent)
        (pictureBtn as? android.widget.Button)?.setTextColor(AppTheme.accent)
        pictureBtn.setOnClickListener { showPictureDialog() }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                val v = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                infoBox.visibility = v
                pictureBtn.visibility = v
                controllerVisible = visibility == View.VISIBLE
                refreshEpBox()
            }
        )
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(saver)
        handler.removeCallbacks(ticker)
        saveProgress()
        player?.release()
        player = null
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------- history

    private fun saveProgress() {
        val p = player ?: return
        val ch = Session.channels.getOrNull(p.currentMediaItemIndex) ?: return
        if (ch.kind != "live" && p.duration > 0) History.save(ch.url, p.currentPosition, p.duration)
    }

    // ------------------------------------------------------------ playback

    private fun startPlayer() {
        val channels = Session.channels
        if (channels.isEmpty()) {
            finish()
            return
        }
        val index = intent.getIntExtra("index", 0).coerceIn(0, channels.lastIndex)
        val first = channels[index]

        // Resume movies/episodes where they were left.
        val saved = if (first.kind != "live") History.posOf(first.url) else 0L
        val prog = History.progress(first.url)
        val restart = intent.getBooleanExtra("restart", false)
        val startPos = if (!restart && saved > 10_000 && prog in 0..94) saved - 3_000 else 0L
        History.touch(first)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 40_000, 2_000, 4_000).build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, http))
            )
            .build()

        exo.setMediaItems(channels.map { it.toMediaItem() }, index, startPos)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
                val p = player ?: return
                Session.channels.getOrNull(p.currentMediaItemIndex)?.let { History.touch(it) }
                loadEpg()
                nearEnd = false
                refreshEpBox()
            }

            override fun onPlayerError(error: PlaybackException) {
                val p = player ?: return
                val i = p.currentMediaItemIndex
                if (error.cause is BehindLiveWindowException) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                // Retry ladder: 1) force HLS, 2) swap .ts <-> .m3u8, 3) show what the server returned.
                val cur = p.currentMediaItem ?: return
                val url = cur.localConfiguration?.uri?.toString().orEmpty()
                val now = SystemClock.elapsedRealtime()
                lastErrorAt = now
                val pos = p.currentPosition
                val isVod = url.contains("/movie/") || url.contains("/series/")
                // The stream dropped while playing (e.g. the server expired the session): reconnect
                // with a fresh session and carry on from the same position.
                if (isVod && pos > 3_000 && now - lastRecoverAt > 10_000) {
                    lastRecoverAt = now
                    android.widget.Toast.makeText(this@PlayerActivity, R.string.reconnecting, android.widget.Toast.LENGTH_SHORT).show()
                    handler.postDelayed({
                        if (player === p) {
                            p.seekTo(pos)
                            p.prepare()
                            p.play()
                        }
                    }, 800)
                    return
                }
                val raw = stage[i] ?: 0
                stage[i] = raw + 1
                // Movies/episodes: the server often needs a moment to prepare the stream, so the first
                // failure is retried unchanged after a short wait (2s, then 4s) before trying other formats.
                val vodFirst = url.contains("/movie/") || url.contains("/series/")
                if (vodFirst && raw < 2) {
                    handler.postDelayed({
                        if (player === p) {
                            p.prepare()
                            p.play()
                        }
                    }, if (raw == 0) 2_000L else 4_000L)
                    return
                }
                val s = if (vodFirst) raw - 2 else raw
                val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
                val base = url.substringBeforeLast('.')
                val vodLike = url.contains("/movie/") || url.contains("/series/")
                fun swap(newExt: String) = cur.buildUpon().setUri("$base.$newExt").setMimeType(null).build()
                val next: MediaItem? = when (s) {
                    0 -> cur.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
                    1 -> when {
                        ext == "ts" -> swap("m3u8")
                        ext == "m3u8" -> swap("ts")
                        vodLike -> swap("m3u8")
                        else -> null
                    }
                    2 -> if (vodLike && ext == "m3u8") swap("ts") else null
                    else -> null
                }
                if (next != null) {
                    val resumePos = p.currentPosition
                    p.replaceMediaItem(i, next)
                    if (resumePos > 0) p.seekTo(i, resumePos)
                    p.prepare()
                    p.play()
                    return
                }
                diagnose(url, error)
            }
        })
        titleView.text = first.name
        playerView.player = exo
        player = exo
        applyPicture()
        exo.prepare()
        exo.playWhenReady = true
        handler.postDelayed(saver, 5_000)
        handler.postDelayed(ticker, 1_000)
        loadEpg()
        refreshEpBox()
    }

    // ------------------------------------------------------------ episodes

    private fun isEpisodes(): Boolean {
        val p = player ?: return false
        return Session.channels.size > 1 && Session.channels.getOrNull(p.currentMediaItemIndex)?.kind == "series"
    }

    /** "All episodes" / "Next episode" buttons: with the controls, and automatically near the end. */
    private fun refreshEpBox() {
        val p = player
        epBox.visibility = if (isEpisodes() && (controllerVisible || nearEnd)) View.VISIBLE else View.GONE
        nextBtn.visibility = if (p != null && p.hasNextMediaItem()) View.VISIBLE else View.GONE
    }

    private fun checkNearEnd() {
        val p = player ?: return
        val near = isEpisodes() && p.hasNextMediaItem() && p.duration > 0 &&
            p.duration - p.currentPosition in 0..30_000
        if (near != nearEnd) {
            nearEnd = near
            refreshEpBox()
        }
    }

    private fun showAllEpisodes() {
        val p = player ?: return
        val names = Session.channels.map { it.name }.toTypedArray()
        val cur = p.currentMediaItemIndex
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.all_episodes)
            .setSingleChoiceItems(names, cur) { d, which ->
                p.seekTo(which, 0L)
                p.play()
                d.dismiss()
            }
            .setOnDismissListener { hideSystemBars() }
            .create()
        dlg.setOnShowListener { dlg.listView.setSelection(cur) }
        dlg.show()
    }

    // ----------------------------------------------------------------- EPG

    /** Shows "now / next" program for live Xtream channels, when the provider supplies a guide. */
    private fun loadEpg() {
        val p = player ?: return
        val ch = Session.channels.getOrNull(p.currentMediaItemIndex) ?: return
        epgView.text = ""
        if (ch.kind != "live") return
        Epg.get(ch) { info ->
            val cur = player?.let { Session.channels.getOrNull(it.currentMediaItemIndex) }
            if (cur?.url == ch.url && info != null) {
                val lines = ArrayList<String>()
                if (info.now.isNotEmpty()) lines.add(getString(R.string.epg_now, info.now))
                if (info.next.isNotEmpty()) lines.add(getString(R.string.epg_next, info.next))
                epgView.text = lines.joinToString("\n")
            }
        }
    }

    // ------------------------------------------------------------- picture

    /** Brightness / contrast / saturation through a color matrix on the video surface (works on every device). */
    private fun applyPicture() {
        val b = prefs.getInt("pic_b", 0)
        val c = prefs.getInt("pic_c", 0)
        val s = prefs.getInt("pic_s", 0)
        val tv = playerView.videoSurfaceView as? TextureView
        if (tv != null) {
            if (b == 0 && c == 0 && s == 0) {
                tv.setLayerType(View.LAYER_TYPE_NONE, null)
            } else {
                val cm = ColorMatrix().apply { setSaturation((1f + s / 100f).coerceAtLeast(0f)) }
                val scale = (1f + c / 100f).coerceAtLeast(0f)
                val translate = (1f - scale) * 127.5f + b * 0.6f
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                tv.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            }
        }
        playerView.resizeMode = when (prefs.getInt("pic_resize", 0)) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun showPictureDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_picture, null)
        val sbB = v.findViewById<SeekBar>(R.id.sb_brightness)
        val sbC = v.findViewById<SeekBar>(R.id.sb_contrast)
        val sbS = v.findViewById<SeekBar>(R.id.sb_saturation)
        sbB.progress = prefs.getInt("pic_b", 0) + 100
        sbC.progress = prefs.getInt("pic_c", 0) + 100
        sbS.progress = prefs.getInt("pic_s", 0) + 100

        fun bind(sb: SeekBar, key: String) {
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.edit().putInt(key, value - 100).apply() // saved on every change (remote or touch)
                    applyPicture()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        for (sb in listOf(sbB, sbC, sbS)) sb.keyProgressIncrement = 5
        bind(sbB, "pic_b")
        bind(sbC, "pic_c")
        bind(sbS, "pic_s")

        fun preset(b: Int, c: Int, s: Int) {
            sbB.progress = b + 100
            sbC.progress = c + 100
            sbS.progress = s + 100
            prefs.edit().putInt("pic_b", b).putInt("pic_c", c).putInt("pic_s", s).apply()
            applyPicture()
        }
        v.findViewById<View>(R.id.preset_normal).setOnClickListener { preset(0, 0, 0) }
        v.findViewById<View>(R.id.preset_vivid).setOnClickListener { preset(0, 25, 35) }
        v.findViewById<View>(R.id.preset_cinema).setOnClickListener { preset(-10, 30, -10) }
        v.findViewById<View>(R.id.preset_bright).setOnClickListener { preset(25, 10, 10) }

        val rg = v.findViewById<RadioGroup>(R.id.rg_resize)
        rg.check(
            when (prefs.getInt("pic_resize", 0)) {
                1 -> R.id.rb_fill
                2 -> R.id.rb_zoom
                else -> R.id.rb_fit
            }
        )
        rg.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rb_fill -> 1
                R.id.rb_zoom -> 2
                else -> 0
            }
            prefs.edit().putInt("pic_resize", mode).apply()
            applyPicture()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.picture_title)
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                applyPicture()
                hideSystemBars()
            }
            .show()
    }

    // ----------------------------------------------------------- diagnosis

    private fun probe(urlStr: String, limit: Int): Triple<Int, String, ByteArray> {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            val code = c.responseCode
            val type = c.contentType ?: "?"
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val buf = ByteArray(limit)
            var n = 0
            stream?.use { st ->
                while (n < limit) {
                    val r = st.read(buf, n, limit - n)
                    if (r <= 0) break
                    n += r
                }
            }
            return Triple(code, type, buf.copyOf(n))
        } finally {
            c.disconnect()
        }
    }

    private fun printable(b: ByteArray, max: Int): String =
        if (b.isEmpty()) "(empty)"
        else String(b, 0, minOf(b.size, max), Charsets.ISO_8859_1).map { if (it.code in 32..126) it else '.' }.joinToString("")

    /** Asks the server directly what it returns (and what its first HLS segment is), to explain a failure. */
    private fun diagnose(url: String, error: PlaybackException) {
        Thread {
            val info = try {
                val (code, type, body) = probe(url, 4096)
                val sb = StringBuilder("HTTP $code\n$type\n${printable(body, 160)}")
                val text = String(body, Charsets.ISO_8859_1)
                if (text.startsWith("#EXTM3U")) {
                    val seg = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (seg != null) {
                        val segUrl = URL(URL(url), seg).toString()
                        val (sc, st, sb2) = probe(segUrl, 1024)
                        val sync = if (sb2.isNotEmpty() && sb2[0] == 0x47.toByte()) "TS sync OK" else "NOT MPEG-TS"
                        sb.append("\n\n--- first segment ---\nHTTP $sc\n$st\n$sync\n${printable(sb2, 100)}")
                    }
                }
                sb.toString()
            } catch (e: Exception) {
                e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            runOnUiThread {
                if (!isFinishing) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(error.errorCodeName)
                        .setMessage(info)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.open_external) { _, _ -> openExternal(url) }
                        .show()
                }
            }
        }.start()
    }

    /** Fallback: hand the link to another installed player (VLC, MX Player, ...). */
    private fun openExternal(url: String) {
        try {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(android.net.Uri.parse(url), "video/*")
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.no_external_player, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun Channel.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
}
