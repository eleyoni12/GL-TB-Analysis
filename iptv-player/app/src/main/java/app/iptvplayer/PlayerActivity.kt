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

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private val triedHls = HashSet<Int>()

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
                // Many Xtream/other links have no extension: retry once as HLS.
                if (triedHls.add(i)) {
                    val old = p.currentMediaItem
                    if (old != null) {
                        val hls = old.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
                        p.replaceMediaItem(i, hls)
                        p.prepare()
                        p.play()
                        return
                    }
                }
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.playback_error, error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        titleView.text = channels[index].name
        playerView.player = exo
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    private fun Channel.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
}
