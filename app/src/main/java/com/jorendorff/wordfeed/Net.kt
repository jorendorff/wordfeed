package com.jorendorff.wordfeed

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/141.0 Mobile Safari/537.36"

class HttpResult(
    val notModified: Boolean,
    val body: String,
    val etag: String?,
    val lastModified: String?,
)

/**
 * GETs a URL, decoding the body with the charset from Content-Type (UTF-8 if
 * none). If [etag] or [lastModified] is given, sends a conditional request,
 * and a 304 comes back as notModified with an empty body.
 */
fun httpGet(url: String, etag: String? = null, lastModified: String? = null): HttpResult {
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
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
            if (lastModified != null) conn.setRequestProperty("If-Modified-Since", lastModified)
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return HttpResult(true, "", etag, lastModified)
            } else if (code in 300..399) {
                // HttpURLConnection won't follow http <-> https redirects itself.
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code with no Location")
                current = URL(URL(current), location).toString()
            } else if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                return HttpResult(
                    notModified = false,
                    body = String(bytes, charsetOf(conn.contentType)),
                    etag = conn.getHeaderField("ETag"),
                    lastModified = conn.getHeaderField("Last-Modified"),
                )
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

/**
 * A small disk cache, to go easy on the sites we read.
 *
 * A response younger than maxAgeMs is used without touching the network.
 * Older ones are revalidated with If-None-Match / If-Modified-Since. If the
 * network fails, a cached copy of any age is better than nothing.
 */
class HttpCache(cacheDir: File) {
    private val dir = File(cacheDir, "http").apply { mkdirs() }

    fun get(url: String, maxAgeMs: Long): String {
        val key = sha1(url)
        val bodyFile = File(dir, "$key.body")
        val metaFile = File(dir, "$key.json")
        val meta = try {
            if (bodyFile.exists() && metaFile.exists()) JSONObject(metaFile.readText()) else null
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        if (meta != null && now - meta.optLong("fetchedAt") < maxAgeMs) {
            return bodyFile.readText()
        }

        val result = try {
            httpGet(
                url,
                etag = meta?.optString("etag")?.ifEmpty { null },
                lastModified = meta?.optString("lastModified")?.ifEmpty { null },
            )
        } catch (e: IOException) {
            if (meta != null) return bodyFile.readText()
            throw e
        }

        if (result.notModified && meta != null) {
            meta.put("fetchedAt", now)
            metaFile.writeText(meta.toString())
            return bodyFile.readText()
        }
        // Body first, then metadata, so metadata never describes a missing body.
        bodyFile.writeText(result.body)
        metaFile.writeText(
            JSONObject()
                .put("url", url)
                .put("fetchedAt", now)
                .put("etag", result.etag ?: "")
                .put("lastModified", result.lastModified ?: "")
                .toString()
        )
        return result.body
    }

    /** Deletes entries not used in [maxAgeMs]. */
    fun prune(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
