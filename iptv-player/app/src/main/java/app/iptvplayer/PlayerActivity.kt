package app.iptvplayer

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private val stage = HashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.title)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                titleView.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
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

    private fun startPlayer() {
        val channels = Session.channels
        if (channels.isEmpty()) {
            finish()
            return
        }
        val index = intent.getIntExtra("index", 0).coerceIn(0, channels.lastIndex)

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

        exo.setMediaItems(channels.map { it.toMediaItem() }, index, 0L)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
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
        titleView.text = channels[index].name
        playerView.player = exo
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }


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
