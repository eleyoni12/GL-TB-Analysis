package app.iptvplayer

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
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
    private lateinit var prefs: SharedPreferences
    private val stage = HashMap<Int, Int>()
    private val handler = Handler(Looper.getMainLooper())
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
        titleView.setTextColor(AppTheme.accent)
        (pictureBtn as? android.widget.Button)?.setTextColor(AppTheme.accent)
        pictureBtn.setOnClickListener { showPictureDialog() }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                val v = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                infoBox.visibility = v
                pictureBtn.visibility = v
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
                val s = stage[i] ?: 0
                stage[i] = s + 1
                val next: MediaItem? = when (s) {
                    0 -> cur.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
                    1 -> when {
                        url.endsWith(".ts") -> cur.buildUpon().setUri(url.removeSuffix(".ts") + ".m3u8").setMimeType(null).build()
                        url.endsWith(".m3u8") -> cur.buildUpon().setUri(url.removeSuffix(".m3u8") + ".ts").setMimeType(null).build()
                        else -> null
                    }
                    else -> null
                }
                if (next != null) {
                    p.replaceMediaItem(i, next)
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
        loadEpg()
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

    private fun applyPicture() {
        val p = player ?: return
        val b = prefs.getInt("pic_b", 0)
        val c = prefs.getInt("pic_c", 0)
        val s = prefs.getInt("pic_s", 0)
        val fx = ArrayList<Effect>()
        if (b != 0) fx.add(Brightness(b / 200f))
        if (c != 0) fx.add(Contrast(c / 200f))
        if (s != 0) fx.add(HslAdjustment.Builder().adjustSaturation(s.toFloat()).build())
        p.setVideoEffects(fx)
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
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt(key, sb.progress - 100).apply()
                    applyPicture()
                }
            })
        }
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
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    // ----------------------------------------------------------- diagnosis

    /** Asks the server directly what it returns, to explain a playback failure. */
    private fun diagnose(url: String, error: PlaybackException) {
        Thread {
            val info = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                val code = c.responseCode
                val type = c.contentType ?: "?"
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val buf = ByteArray(200)
                val n = stream?.use { it.read(buf) } ?: 0
                c.disconnect()
                val text = if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1)
                    .map { if (it.code in 32..126) it else '.' }.joinToString("") else "(empty)"
                "HTTP $code\n$type\n$text"
            } catch (e: Exception) {
                e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            runOnUiThread {
                if (!isFinishing) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(error.errorCodeName)
                        .setMessage(info)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun Channel.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
}
