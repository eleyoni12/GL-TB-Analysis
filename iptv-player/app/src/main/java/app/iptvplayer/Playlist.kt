package app.iptvplayer

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL

/** kind: "live", "vod" (movies) or "series". */
data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val kind: String = "live"
)

/** Holds the channel list currently playing, so the player can zap next/previous. */
object Session {
    var channels: List<Channel> = emptyList()
}

object Playlist {
    private const val UA = "VLC/3.0.18 LibVLC/3.0.18"
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    const val SERIES_SCHEME = "xtream-series://"

    // ------------------------------------------------------------ networking

    /** Downloads [urlStr] into [dest], following http<->https redirects. */
    fun download(urlStr: String, dest: File) {
        val tmp = File(dest.path + ".tmp")
        var current = urlStr
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", UA)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location")
                    current = URL(URL(current), loc).toString()
                    if (++redirects > 6) throw IOException("Too many redirects")
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Cannot save playlist")
            return
        }
    }

    /** Returns (base, user, pass) when [urlStr] is an Xtream get.php link. */
    fun xtreamCreds(urlStr: String): Triple<String, String, String>? {
        if (!urlStr.contains("get.php")) return null
        val u = runCatching { java.net.URI(urlStr) }.getOrNull() ?: return null
        val params = u.rawQuery.orEmpty().split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val user = params["username"]
        val pass = params["password"]
        if (user.isNullOrBlank() || pass.isNullOrBlank() || u.scheme == null || u.rawAuthority == null) return null
        val base = "${u.scheme}://${u.rawAuthority}" + u.rawPath.orEmpty().substringBeforeLast("/get.php").trimEnd('/')
        return Triple(base, user, pass)
    }

    class LoginException(msg: String) : IOException(msg)

    /** Returns the URL that actually worked (an http link may only work as https). */
    fun downloadSmart(urlStr: String, dest: File): String {
        try {
            downloadSmartOnce(urlStr, dest)
            return urlStr
        } catch (e: Exception) {
            if (e !is LoginException && urlStr.startsWith("http://") && xtreamCreds(urlStr) != null) {
                val https = "https://" + urlStr.removePrefix("http://")
                try {
                    downloadSmartOnce(https, dest)
                    return https
                } catch (_: Exception) {
                }
            }
            throw e
        }
    }

    /**
     * For Xtream links, builds the full list (live + movies + series) through player_api.php,
     * and falls back to the plain get.php playlist if that fails. Other URLs download as-is.
     */
    private fun downloadSmartOnce(urlStr: String, dest: File) {
        val creds = xtreamCreds(urlStr)
        if (creds == null) {
            download(urlStr, dest)
            return
        }
        try {
            xtreamViaApi(creds.first, creds.second, creds.third, dest)
        } catch (login: LoginException) {
            throw login
        } catch (apiError: Exception) {
            try {
                download(urlStr, dest)
            } catch (e: IOException) {
                throw IOException("player_api: ${apiError.message} | get.php: ${e.message}")
            }
        }
    }

    private fun checkLogin(api: String, tmp: File) {
        download(api, tmp)
        val obj = try {
            JSONObject(tmp.readText())
        } catch (e: Exception) {
            throw IOException("الرد ليس بيانات Xtream، تأكد من السيرفر والبورت")
        }
        val ui = obj.optJSONObject("user_info") ?: throw IOException("السيرفر لا يدعم Xtream API")
        val auth = ui.opt("auth")?.toString()
        if (auth == "0" || auth == "false") throw LoginException("اسم المستخدم أو كلمة المرور غير صحيحة")
        val status = ui.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw LoginException("حالة الاشتراك: $status")
        }
    }

    // ---------------------------------------------------------- Xtream API

    private fun clean(s: String) = s.replace("\"", "").replace(Regex("[\\r\\n]"), " ")

    private fun emit(w: Writer, kind: String, name: String, logo: String, group: String, url: String) {
        w.write("#EXTINF:-1 x-kind=\"$kind\" tvg-logo=\"${clean(logo)}\" group-title=\"${clean(group)}\",${clean(name)}\n")
        w.write(url + "\n")
    }

    private fun forEachObject(file: File, action: (Map<String, String>) -> Unit) {
        JsonReader(file.bufferedReader()).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Unexpected response")
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue(); continue
                }
                val m = HashMap<String, String>()
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                        JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                action(m)
            }
            r.endArray()
        }
    }

    private fun xtreamViaApi(base: String, user: String, pass: String, dest: File) {
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val api = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val tmp = File(dest.path + ".json")
        val gen = File(dest.path + ".gen")
        var total = 0
        var firstError: Exception? = null
        try {
            checkLogin(api, tmp)
            gen.bufferedWriter().use { w ->
                w.write("#EXTM3U\n")

                fun section(
                    kind: String, catAction: String, listAction: String,
                    id: (Map<String, String>) -> String?, url: (Map<String, String>, String) -> String,
                    logoKey: String
                ) {
                    try {
                        val cats = HashMap<String, String>()
                        download("$api&action=$catAction", tmp)
                        forEachObject(tmp) { cats[it["category_id"].orEmpty()] = it["category_name"].orEmpty() }
                        download("$api&action=$listAction", tmp)
                        forEachObject(tmp) { m ->
                            val sid = id(m) ?: return@forEachObject
                            emit(w, kind, m["name"].orEmpty(), m[logoKey].orEmpty(), cats[m["category_id"].orEmpty()].orEmpty(), url(m, sid))
                            total++
                        }
                    } catch (e: Exception) {
                        if (firstError == null) firstError = e
                    }
                }

                section("live", "get_live_categories", "get_live_streams",
                    { it["stream_id"]?.takeIf { s -> s.isNotEmpty() } },
                    { _, sid -> "$base/live/${enc(user)}/${enc(pass)}/$sid.ts" }, "stream_icon")
                section("vod", "get_vod_categories", "get_vod_streams",
                    { it["stream_id"]?.takeIf { s -> s.isNotEmpty() } },
                    { m, sid -> "$base/movie/${enc(user)}/${enc(pass)}/$sid.${m["container_extension"]?.ifBlank { null } ?: "mp4"}" }, "stream_icon")
                section("series", "get_series_categories", "get_series",
                    { it["series_id"]?.takeIf { s -> s.isNotEmpty() } },
                    { _, sid -> SERIES_SCHEME + sid }, "cover")
            }
            if (total == 0) throw IOException(firstError?.message ?: "no items returned (check login)")
            dest.delete()
            if (!gen.renameTo(dest)) throw IOException("Cannot save playlist")
        } finally {
            tmp.delete()
            gen.delete()
        }
    }

    /** Fetches the episodes of one Xtream series. */
    fun fetchEpisodes(srcUrl: String, seriesId: String, seriesName: String, logo: String?, workDir: File): List<Channel> {
        val (base, user, pass) = xtreamCreds(srcUrl) ?: throw IOException("Not an Xtream source")
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val tmp = File(workDir, "series_info.json")
        try {
            download("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_series_info&series_id=${enc(seriesId)}", tmp)
            val root = JSONObject(tmp.readText())
            val found = ArrayList<JSONObject>()
            collectEpisodes(root.opt("episodes"), found)
            val eps = found.sortedWith(compareBy({ it.optInt("season") }, { it.optInt("episode_num") }))
            return eps.map { e ->
                val id = e.optString("id")
                val ext = e.optString("container_extension").ifBlank { "mp4" }
                val title = e.optString("title").ifBlank { "Episode ${e.optInt("episode_num")}" }
                val img = e.optJSONObject("info")?.optString("movie_image")?.takeIf { it.isNotBlank() } ?: logo
                Channel(
                    name = "S${e.optInt("season")}E${e.optInt("episode_num")}  $title",
                    url = "$base/series/${enc(user)}/${enc(pass)}/$id.$ext",
                    group = seriesName,
                    logo = img,
                    kind = "series"
                )
            }
        } finally {
            tmp.delete()
        }
    }

    private fun collectEpisodes(x: Any?, out: MutableList<JSONObject>) {
        when (x) {
            is JSONObject -> if (x.has("id") && x.has("container_extension")) out.add(x)
                else x.keys().forEach { collectEpisodes(x.opt(it), out) }
            is JSONArray -> for (i in 0 until x.length()) collectEpisodes(x.opt(i), out)
        }
    }

    // --------------------------------------------------------------- parsing

    fun parse(file: File): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        var logo: String? = null
        var kind: String? = null

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.removePrefix("﻿").trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val comma = firstCommaOutsideQuotes(line)
                        val attrs = if (comma >= 0) line.substring(0, comma) else line
                        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        val map = ATTR.findAll(attrs).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        name = title.ifEmpty { map["tvg-name"].orEmpty() }
                        group = map["group-title"].orEmpty()
                        logo = map["tvg-logo"]?.takeIf { it.isNotBlank() }
                        kind = map["x-kind"]
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        if (group.isBlank()) group = line.substringAfter(':').trim()
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        if (line.contains("://")) {
                            val k = kind ?: when {
                                line.contains("/movie/") -> "vod"
                                line.contains("/series/") -> "series"
                                else -> "live"
                            }
                            out.add(
                                Channel(
                                    name = name?.ifBlank { null } ?: line.substringAfterLast('/'),
                                    url = line,
                                    group = group.ifBlank { "Other" },
                                    logo = logo,
                                    kind = k
                                )
                            )
                        }
                        name = null; group = ""; logo = null; kind = null
                    }
                }
            }
        }
        return out
    }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return i
        }
        return -1
    }

    fun xtreamUrl(server: String, user: String, pass: String): String {
        var s = server.trim().substringBefore('?')
        if (!s.contains("://")) s = "http://$s"
        s = s.trimEnd('/')
        // Drop panel paths users often paste (…/c, …/get.php, …/player_api.php, …/portal.php)
        s = s.replace(Regex("/(c|get\\.php|player_api\\.php|panel_api\\.php|portal\\.php|stalker_portal/c)$"), "").trimEnd('/')
        return "$s/get.php?username=${java.net.URLEncoder.encode(user.trim(), "UTF-8")}" +
            "&password=${java.net.URLEncoder.encode(pass.trim(), "UTF-8")}&type=m3u_plus&output=ts"
    }
}
