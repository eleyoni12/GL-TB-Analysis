package app.iptvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Plays the Aey intro video (with sound). Tap or press any key to skip. */
class SplashActivity : AppCompatActivity() {

    private var done = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val video = findViewById<VideoView>(R.id.video)
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.intro}"))
        video.setOnCompletionListener { next() }
        video.setOnErrorListener { _, _, _ -> next(); true }
        video.start()

        findViewById<View>(R.id.splash_root).setOnClickListener { next() }
        handler.postDelayed({ next() }, 8_000) // safety: never get stuck here
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return super.onKeyDown(keyCode, event)
        next()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun next() {
        if (done) return
        done = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
