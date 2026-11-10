package app.iptvplayer

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Now/next program guide for Xtream live channels (player_api get_short_epg). */
object Epg {
    class Info(val now: String, val next: String)

    @Volatile
    var source: String? = null // the Xtream get.php link the list was loaded from

    private val cache = ConcurrentHashMap<String, Pair<Long, Info?>>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val liveId = Regex("/live/[^/]+/[^/]+/(\\d+)\\.\\w+$")

    fun get(ch: Channel, cb: (Info?) -> Unit) {
        if (ch.kind != "live") return
        val id = liveId.find(ch.url)?.groupValues?.get(1) ?: return
        val src = source ?: return
        val hit = cache[id]
        if (hit != null && System.currentTimeMillis() - hit.first < 5 * 60_000) {
            cb(hit.second)
            return
        }
        pool.execute {
            val info = try { fetch(src, id) } catch (_: Exception) { null }
            cache[id] = System.currentTimeMillis() to info
            main.post { cb(info) }
        }
    }

    private fun decode(s: String): String =
        try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim() } catch (_: Exception) { s }

    private fun fetch(src: String, id: String): Info? {
        val (base, user, pass) = Playlist.xtreamCreds(src) ?: return null
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val url = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_short_epg&stream_id=$id&limit=3"
        val c = URL(url).openConnection() as HttpURLConnection
        val body = try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val arr = JSONObject(body).optJSONArray("epg_listings") ?: return null
        val now = System.currentTimeMillis() / 1000
        var cur: String? = null
        var next: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val start = o.optString("start_timestamp").toLongOrNull() ?: continue
            val stop = o.optString("stop_timestamp").toLongOrNull() ?: continue
            val title = decode(o.optString("title"))
            if (start <= now && now < stop) cur = title
            else if (start >= now && next == null) next = title
        }
        return if (cur == null && next == null) null else Info(cur.orEmpty(), next.orEmpty())
    }
}
