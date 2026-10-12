package com.jorendorff.wordfeed

import android.content.Context

/** Subscriptions, custom feeds, and read state, kept in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("wordfeed", Context.MODE_PRIVATE)

    fun isSubscribed(s: Source): Boolean = prefs.getBoolean("sub.${s.id}", s.defaultOn)

    fun setSubscribed(s: Source, on: Boolean) {
        prefs.edit().putBoolean("sub.${s.id}", on).apply()
    }

    fun seen(id: String): String? = prefs.getString("seen.$id", null)

    fun setSeen(id: String, guid: String) {
        prefs.edit().putString("seen.$id", guid).apply()
    }

    fun customSources(): List<Source> =
        (prefs.getString("custom", null) ?: "")
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2) null else customSource(parts[0], parts[1])
            }

    fun addCustom(name: String, url: String): Source {
        val cleanName = name.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        val cleanUrl = url.trim()
        val lines = customSources()
            .filter { it.feedUrl != cleanUrl }
            .map { "${it.name}\t${it.feedUrl}" } + "$cleanName\t$cleanUrl"
        prefs.edit().putString("custom", lines.joinToString("\n")).apply()
        return customSource(cleanName, cleanUrl)
    }

    fun removeCustom(s: Source) {
        val lines = customSources()
            .filter { it.id != s.id }
            .map { "${it.name}\t${it.feedUrl}" }
        prefs.edit()
            .putString("custom", lines.joinToString("\n"))
            .remove("sub.${s.id}")
            .remove("seen.${s.id}")
            .apply()
    }

    private fun customSource(name: String, url: String) = Source(
        id = "custom:$url",
        name = name,
        shortName = if (name.length > 18) name.take(17) + "…" else name,
        blurb = url,
        feedUrl = url,
        custom = true,
    )
}
