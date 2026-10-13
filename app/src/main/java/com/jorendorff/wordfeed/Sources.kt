package com.jorendorff.wordfeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A place to get words from. [json] is the full definition handed to the
 * reader page (see assets/sources.json); the rest is what the Compose UI needs.
 */
data class Source(
    val id: String,
    val name: String,
    val shortName: String,
    val blurb: String,
    val json: String,
    val defaultOn: Boolean = true,
    val custom: Boolean = false,
)

fun loadBuiltinSources(context: Context): List<Source> {
    val text = context.assets.open("sources.json").bufferedReader().use { it.readText() }
    val array = JSONArray(text)
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Source(
            id = o.getString("id"),
            name = o.getString("name"),
            shortName = o.optString("short", o.getString("name")),
            blurb = o.optString("blurb", ""),
            json = o.toString(),
            defaultOn = o.optBoolean("default", true),
        )
    }
}

fun customSource(name: String, url: String): Source {
    val id = "custom:$url"
    val short = if (name.length > 18) name.take(17) + "…" else name
    val json = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("short", short)
        .put("extractor", "rss")
        .put("url", url)
    return Source(
        id = id,
        name = name,
        shortName = short,
        blurb = url,
        json = json.toString(),
        custom = true,
    )
}
