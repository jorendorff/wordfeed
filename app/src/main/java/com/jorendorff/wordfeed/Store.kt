package com.jorendorff.wordfeed

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Subscriptions, custom feeds, read state, and settings, in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("wordfeed", Context.MODE_PRIVATE)

    fun isSubscribed(s: Source): Boolean = prefs.getBoolean("sub.${s.id}", s.defaultOn)

    fun setSubscribed(s: Source, on: Boolean) {
        prefs.edit().putBoolean("sub.${s.id}", on).apply()
    }

    fun seen(id: String): String? = prefs.getString("seen.$id", null)

    fun setSeen(id: String, key: String) {
        prefs.edit().putString("seen.$id", key).apply()
    }

    var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM")
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        set(value) {
            prefs.edit().putString("theme", value.name).apply()
        }

    private fun customLines(): List<Pair<String, String>> =
        (prefs.getString("custom", null) ?: "")
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2) null else parts[0] to parts[1]
            }

    fun customSources(): List<Source> = customLines().map { (name, url) -> customSource(name, url) }

    fun addCustom(name: String, url: String): Source {
        val cleanName = name.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        val cleanUrl = url.trim()
        val lines = customLines().filter { it.second != cleanUrl } + (cleanName to cleanUrl)
        prefs.edit()
            .putString("custom", lines.joinToString("\n") { "${it.first}\t${it.second}" })
            .apply()
        return customSource(cleanName, cleanUrl)
    }

    fun removeCustom(s: Source) {
        val lines = customLines().filter { customSource(it.first, it.second).id != s.id }
        prefs.edit()
            .putString("custom", lines.joinToString("\n") { "${it.first}\t${it.second}" })
            .remove("sub.${s.id}")
            .remove("seen.${s.id}")
            .apply()
    }
}
