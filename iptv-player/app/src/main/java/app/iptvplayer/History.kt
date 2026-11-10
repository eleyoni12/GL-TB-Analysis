package app.iptvplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** "Continue watching": recently played items plus the position reached in movies/episodes. */
object History {
    private const val KEY = "history"
    private const val MAX = 150

    class Entry(val ch: Channel, var pos: Long, var dur: Long, var ts: Long)

    private var prefs: SharedPreferences? = null
    private val items = ArrayList<Entry>() // most recent first

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        prefs = p
        val s = p.getString(KEY, null) ?: return
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(
                    Entry(
                        Channel(
                            o.getString("n"), o.getString("u"), o.optString("g"),
                            o.optString("l").ifBlank { null }, o.optString("k", "live")
                        ),
                        o.optLong("p"), o.optLong("d"), o.optLong("t")
                    )
                )
            }
        } catch (_: Exception) {
            items.clear()
        }
    }

    private fun persist() {
        val a = JSONArray()
        for (e in items) {
            a.put(
                JSONObject()
                    .put("n", e.ch.name).put("u", e.ch.url).put("g", e.ch.group)
                    .put("l", e.ch.logo ?: "").put("k", e.ch.kind)
                    .put("p", e.pos).put("d", e.dur).put("t", e.ts)
            )
        }
        prefs?.edit()?.putString(KEY, a.toString())?.apply()
    }

    private fun find(url: String) = items.firstOrNull { it.ch.url == url }

    /** Marks [ch] as just played (moves it to the top, keeps its saved position). */
    @Synchronized
    fun touch(ch: Channel) {
        val e = find(ch.url)
        if (e != null) {
            items.remove(e)
            e.ts = System.currentTimeMillis()
            items.add(0, e)
        } else {
            items.add(0, Entry(ch, 0, 0, System.currentTimeMillis()))
        }
        while (items.size > MAX) items.removeAt(items.lastIndex)
        persist()
    }

    @Synchronized
    fun save(url: String, pos: Long, dur: Long) {
        val e = find(url) ?: return
        e.pos = pos
        e.dur = dur
        persist()
    }

    @Synchronized
    fun posOf(url: String): Long = find(url)?.pos ?: 0L

    /** Percent watched (0..100), or -1 if unknown. */
    @Synchronized
    fun progress(url: String): Int {
        val e = find(url) ?: return -1
        if (e.dur <= 0 || e.pos <= 0) return -1
        return (e.pos * 100 / e.dur).toInt().coerceIn(0, 100)
    }

    /** Live: recently watched channels. Movies/series: unfinished items. */
    @Synchronized
    fun recent(kind: String): List<Channel> =
        items.filter { e ->
            e.ch.kind == kind && (kind == "live" || (e.pos > 10_000 && e.dur > 0 && e.pos * 100 / e.dur < 95))
        }.map { it.ch }
}
