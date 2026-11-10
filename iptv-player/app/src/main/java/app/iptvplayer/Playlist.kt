package app.iptvplayer

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?
)

/** Holds the channel list currently shown, so the player can zap next/previous. */
object Session {
    var channels: List<Channel> = emptyList()
}

object Playlist {
    private const val UA = "VLC/3.0.18 LibVLC/3.0.18"
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

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
                conn.readTimeout = 30_000
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

    /** Like [download], but if an Xtream get.php link fails, falls back to player_api.php. */
    fun downloadSmart(urlStr: String, dest: File) {
        try {
            download(urlStr, dest)
        } catch (e: IOException) {
            val u = runCatching { java.net.URI(urlStr) }.getOrNull()
            val query = u?.rawQuery.orEmpty()
            val params = query.split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val user = params["username"]
            val pass = params["password"]
            if (u == null || user.isNullOrBlank() || pass.isNullOrBlank() || !urlStr.contains("get.php")) throw e
            val base = "${u.scheme}://${u.rawAuthority}" + u.rawPath.substringBeforeLast("/get.php").trimEnd('/')
            try {
                xtreamViaApi(base, user, pass, dest)
            } catch (e2: Exception) {
                throw IOException("get.php: ${e.message} | player_api: ${e2.message}")
            }
        }
    }

    private fun xtreamViaApi(base: String, user: String, pass: String, dest: File) {
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val api = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val tmp = File(dest.path + ".json")
        try {
            download("$api&action=get_live_categories", tmp)
            val cats = HashMap<String, String>()
            val catArr = org.json.JSONArray(tmp.readText())
            for (i in 0 until catArr.length()) {
                val o = catArr.getJSONObject(i)
                cats[o.optString("category_id")] = o.optString("category_name")
            }
            download("$api&action=get_live_streams", tmp)
            val arr = org.json.JSONArray(tmp.readText())
            val out = File(dest.path + ".gen")
            out.bufferedWriter().use { w ->
                w.write("#EXTM3U\n")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isEmpty()) continue
                    val name = o.optString("name").replace(Regex("[\\r\\n]"), " ")
                    val logo = o.optString("stream_icon").replace("\"", "")
                    val group = (cats[o.optString("category_id")] ?: "").replace("\"", "")
                    w.write("#EXTINF:-1 tvg-logo=\"$logo\" group-title=\"$group\",$name\n")
                    w.write("$base/live/${enc(user)}/${enc(pass)}/$id.ts\n")
                }
            }
            dest.delete()
            if (!out.renameTo(dest)) throw IOException("Cannot save playlist")
        } finally {
            tmp.delete()
        }
    }

    fun parse(file: File): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        var logo: String? = null

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
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        if (group.isBlank()) group = line.substringAfter(':').trim()
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        if (line.contains("://")) {
                            out.add(
                                Channel(
                                    name = name?.ifBlank { null } ?: line.substringAfterLast('/'),
                                    url = line,
                                    group = group.ifBlank { "Other" },
                                    logo = logo
                                )
                            )
                        }
                        name = null; group = ""; logo = null
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
