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
        var s = server.trim().trimEnd('/')
        if (!s.contains("://")) s = "http://$s"
        return "$s/get.php?username=${java.net.URLEncoder.encode(user.trim(), "UTF-8")}" +
            "&password=${java.net.URLEncoder.encode(pass.trim(), "UTF-8")}&type=m3u_plus&output=ts"
    }
}
