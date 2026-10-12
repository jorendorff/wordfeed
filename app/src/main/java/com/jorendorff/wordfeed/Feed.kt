package com.jorendorff.wordfeed

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class Item(
    val guid: String,
    val title: String,
    val link: String?,
    val html: String,
    val dateMillis: Long?,
    val dateRaw: String?,
    val audioUrl: String?,
)

sealed interface SourceContent {
    data class Items(val items: List<Item>) : SourceContent
    data class Page(val url: String) : SourceContent
    data class Failed(val message: String) : SourceContent
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/129.0 Mobile Safari/537.36 Wordfeed/0.1"

suspend fun fetchSource(src: Source): SourceContent = withContext(Dispatchers.IO) {
    var failure: String? = null
    val feedUrl = src.feedUrl
    if (feedUrl != null) {
        try {
            val items = parseFeed(httpGet(feedUrl))
            if (items.isNotEmpty()) {
                return@withContext SourceContent.Items(items)
            }
            failure = "The feed had no items."
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        }
    }
    val pageUrl = src.pageUrl
    if (pageUrl != null) {
        SourceContent.Page(pageUrl)
    } else {
        SourceContent.Failed(failure ?: "Nothing to show.")
    }
}

fun httpGet(url: String): ByteArray {
    var current = url
    repeat(5) {
        val conn = URL(current).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty(
                "Accept",
                "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8",
            )
            val code = conn.responseCode
            if (code in 300..399) {
                // HttpURLConnection won't follow http <-> https redirects itself.
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code with no Location")
                current = URL(URL(current), location).toString()
            } else if (code in 200..299) {
                return conn.inputStream.use { it.readBytes() }
            } else {
                throw IOException("HTTP $code from $current")
            }
        } finally {
            conn.disconnect()
        }
    }
    throw IOException("Too many redirects from $url")
}

/** Parses RSS 2.0, RSS 1.0 (RDF), and Atom. Newest items first. */
fun parseFeed(bytes: ByteArray): List<Item> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(ByteArrayInputStream(bytes), null)

    val items = mutableListOf<Item>()
    var inItem = false
    var fields = mutableMapOf<String, String>()
    var link: String? = null
    var audio: String? = null

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            val name = parser.name
            if (name == "item" || name == "entry") {
                inItem = true
                fields = mutableMapOf()
                link = null
                audio = null
            } else if (inItem) {
                when (name) {
                    "link" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (href == null) {
                            val text = readText(parser).trim()
                            if (text.isNotEmpty() && link == null) link = text
                        } else {
                            val rel = parser.getAttributeValue(null, "rel")
                            if ((rel == null || rel == "alternate") && link == null) link = href
                            if (rel == "enclosure") audio = href
                        }
                    }
                    "enclosure" -> {
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && (type.startsWith("audio") || url.endsWith(".mp3"))) audio = url
                    }
                    "title", "description", "content:encoded", "summary", "content",
                    "pubDate", "published", "updated", "dc:date", "guid", "id",
                    -> fields[name] = readText(parser)
                }
            }
        } else if (event == XmlPullParser.END_TAG) {
            val name = parser.name
            if (inItem && (name == "item" || name == "entry")) {
                inItem = false
                items += buildItem(fields, link, audio)
            }
        }
        event = parser.next()
    }

    val unique = items.distinctBy { it.guid }
    return if (unique.all { it.dateMillis != null }) {
        unique.sortedByDescending { it.dateMillis }
    } else {
        unique
    }
}

/** Reads all text inside the current element, leaving the parser on its end tag. */
private fun readText(p: XmlPullParser): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (p.next()) {
            XmlPullParser.TEXT -> sb.append(p.text)
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return sb.toString()
        }
    }
    return sb.toString()
}

private fun buildItem(fields: Map<String, String>, link: String?, audio: String?): Item {
    val rawTitle = fields["title"]?.trim().orEmpty()
    val title = Html.fromHtml(rawTitle, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        .ifEmpty { "(untitled)" }
    val html = listOf("content:encoded", "content", "description", "summary")
        .firstNotNullOfOrNull { key -> fields[key]?.takeIf { it.isNotBlank() } }
        .orEmpty()
    val dateRaw = listOf("pubDate", "published", "updated", "dc:date")
        .firstNotNullOfOrNull { key -> fields[key]?.trim()?.takeIf { it.isNotEmpty() } }
    val guid = fields["guid"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: fields["id"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: link
        ?: title
    return Item(
        guid = guid,
        title = title,
        link = link,
        html = html,
        dateMillis = parseDate(dateRaw),
        dateRaw = dateRaw,
        audioUrl = audio,
    )
}

private val DATE_PATTERNS = listOf(
    "EEE, dd MMM yyyy HH:mm:ss Z",
    "EEE, dd MMM yyyy HH:mm:ss zzz",
    "dd MMM yyyy HH:mm:ss Z",
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd",
)

fun parseDate(raw: String?): Long? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    for (pattern in DATE_PATTERNS) {
        try {
            val date = SimpleDateFormat(pattern, Locale.US).parse(s)
            if (date != null) return date.time
        } catch (e: Exception) {
            // try the next pattern
        }
    }
    return null
}

/** Strips markup that looks bad in a TextView (fixed colors, fonts, images, scripts). */
fun cleanHtml(html: String): String = html
    .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
    .replace(Regex("(?i)</?font[^>]*>"), "")
    .replace(Regex("(?i)<img[^>]*>"), "")
    .replace(Regex("(?i)\\s(style|color|bgcolor)=\"[^\"]*\""), "")
