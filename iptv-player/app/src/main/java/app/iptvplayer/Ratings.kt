package app.iptvplayer

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Ratings for movies/series that came without one in the list.
 * 1) the provider's own info (player_api get_vod_info / get_series_info),
 * 2) optionally TMDB, if the user entered a TMDB API key in the settings.
 * Results are cached on the device.
 */
object Ratings {
    private var prefs: SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, String>() // url -> rating, "" = looked up, none found
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(40), ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private val persistRunnable = Runnable { persist() }

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        prefs = p
        try {
            val o = JSONObject(p.getString("ratings", null) ?: return)
            for (k in o.keys()) cache[k] = o.optString(k)
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val o = JSONObject()
        for ((k, v) in cache) o.put(k, v)
        prefs?.edit()?.putString("ratings", o.toString())?.apply()
    }

    private fun schedulePersist() {
        main.removeCallbacks(persistRunnable)
        main.postDelayed(persistRunnable, 3_000)
    }

    /** After a refresh, titles that had no rating are tried again. */
    fun clearMisses() {
        cache.entries.removeIf { it.value.isEmpty() }
        persist()
    }

    fun get(ch: Channel, cb: (String) -> Unit) {
        if (ch.kind == "live") return
        cache[ch.url]?.let {
            if (it.isNotEmpty()) cb(it)
            return
        }
        if (!inFlight.add(ch.url)) return
        pool.execute {
            val result = try { fetch(ch) } catch (_: Exception) { null } // null = network error, try again later
            inFlight.remove(ch.url)
            if (result != null) {
                cache[ch.url] = result
                schedulePersist()
                if (result.isNotEmpty()) main.post { cb(result) }
            }
        }
    }

    private fun format(v: Double?): String =
        if (v == null || v <= 0.0) "" else String.format(java.util.Locale.US, "%.1f", v.coerceAtMost(10.0))

    private fun fetch(ch: Channel): String {
        val src = Epg.source
        var r = ""
        if (src != null && Playlist.xtreamCreds(src) != null) r = serverRating(src, ch)
        if (r.isEmpty()) {
            val key = prefs?.getString("tmdb_key", "").orEmpty().trim()
            if (key.isNotEmpty()) r = tmdbRating(ch, key)
        }
        return r
    }

    private fun serverRating(src: String, ch: Channel): String {
        val (base, user, pass) = Playlist.xtreamCreds(src) ?: return ""
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val api = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val info: JSONObject?
        if (ch.url.startsWith(Playlist.SERIES_SCHEME)) {
            val id = ch.url.removePrefix(Playlist.SERIES_SCHEME)
            info = JSONObject(Playlist.httpGetText("$api&action=get_series_info&series_id=${enc(id)}", 15_000)).optJSONObject("info")
        } else {
            val id = Regex("/movie/[^/]+/[^/]+/(\\d+)\\.").find(ch.url)?.groupValues?.get(1) ?: return ""
            info = JSONObject(Playlist.httpGetText("$api&action=get_vod_info&vod_id=${enc(id)}")).optJSONObject("info")
        }
        if (info == null) return ""
        val direct = info.optString("rating").toDoubleOrNull()
        if (direct != null && direct > 0) return format(direct)
        return format(info.optString("rating_5based").toDoubleOrNull()?.times(2))
    }

    private fun cleanTitle(name: String): Pair<String, Int?> {
        val year = Regex("(?:19|20)\\d{2}").find(name)?.value?.toIntOrNull()
        var t = name
        t = t.replace(Regex("^\\s*(AR|EN|TR|FR|ES|DE|IN|KR|NF|NETFLIX|MBC|OSN)\\s*[|:\\-]\\s*", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        t = t.replace(
            Regex("\\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|480p|web-?dl|webrip|bluray|brrip|hdrip|dvdrip|x264|x265|hevc|multi|dubbed|subbed)\\b", RegexOption.IGNORE_CASE),
            " "
        )
        t = t.replace(Regex("مترجم[ةه]?|مدبلج[ةه]?"), " ")
        t = t.replace(Regex("(?:19|20)\\d{2}"), " ")
        t = t.replace(Regex("[|_]+|\\s[-–—]\\s"), " ")
        return t.replace(Regex("\\s+"), " ").trim() to year
    }

    private fun tmdbRating(ch: Channel, key: String): String {
        val isTv = ch.kind == "series"
        val (title, year) = cleanTitle(ch.name)
        if (title.isEmpty()) return ""
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val kind = if (isTv) "tv" else "movie"
        fun search(withYear: Boolean): String {
            var url = "https://api.themoviedb.org/3/search/$kind?api_key=${enc(key)}&query=${enc(title)}&include_adult=false"
            if (withYear && year != null) url += if (isTv) "&first_air_date_year=$year" else "&primary_release_year=$year"
            val results = JSONObject(Playlist.httpGetText(url)).optJSONArray("results") ?: return ""
            if (results.length() == 0) return ""
            return format(results.getJSONObject(0).optDouble("vote_average", 0.0))
        }
        val r = search(true)
        return if (r.isNotEmpty() || year == null) r else search(false)
    }
}
