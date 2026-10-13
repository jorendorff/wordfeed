package com.jorendorff.wordfeed

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/141.0 Mobile Safari/537.36"

/** GETs a URL and decodes it using the charset from Content-Type (UTF-8 if none). */
fun httpGetText(url: String): String {
    var current = url
    repeat(6) {
        val conn = URL(current).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            val code = conn.responseCode
            if (code in 300..399) {
                // HttpURLConnection won't follow http <-> https redirects itself.
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code with no Location")
                current = URL(URL(current), location).toString()
            } else if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                return String(bytes, charsetOf(conn.contentType))
            } else {
                throw IOException("HTTP $code from $current")
            }
        } finally {
            conn.disconnect()
        }
    }
    throw IOException("Too many redirects from $url")
}

private fun charsetOf(contentType: String?): Charset {
    val name = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
        .find(contentType ?: "")
        ?.groupValues
        ?.get(1)
        ?: return Charsets.UTF_8
    return try {
        Charset.forName(name)
    } catch (e: Exception) {
        Charsets.UTF_8
    }
}
