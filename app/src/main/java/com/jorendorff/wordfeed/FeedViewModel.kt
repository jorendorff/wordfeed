package com.jorendorff.wordfeed

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var allSources by mutableStateOf(loadBuiltinSources(app) + store.customSources())
        private set

    var subscribedIds by mutableStateOf(
        allSources.filter { store.isSubscribed(it) }.map { it.id }.toSet()
    )
        private set

    var themeMode by mutableStateOf(store.themeMode)
        private set

    /** Bumped by refresh(); reader pages reload when it changes. */
    var generation by mutableIntStateOf(0)
        private set

    /** Source id -> key of the newest entry its reader page showed ("" if it failed). */
    private val newest = mutableStateMapOf<String, String>()

    /** Source id -> key of the newest entry the user has looked at. */
    private val seen = mutableStateMapOf<String, String>()

    /** URL -> response, shared by all reader pages until the next refresh. Main thread only. */
    private val cache = HashMap<String, Deferred<Result<String>>>()

    val subscribedSources: List<Source>
        get() = allSources.filter { it.id in subscribedIds }

    init {
        for (s in allSources) {
            store.seen(s.id)?.let { seen[s.id] = it }
        }
    }

    suspend fun fetch(url: String): Result<String> {
        val deferred = cache.getOrPut(url) {
            viewModelScope.async(Dispatchers.IO) { runCatching { httpGetText(url) } }
        }
        val result = deferred.await()
        // Don't keep failures around; the next attempt should retry.
        if (result.isFailure && cache[url] === deferred) cache.remove(url)
        return result
    }

    fun refresh() {
        cache.clear()
        newest.clear()
        generation++
    }

    fun isLoading(s: Source): Boolean = s.id !in newest

    fun newestKey(s: Source): String? = newest[s.id]

    fun reportRendered(s: Source, key: String) {
        newest[s.id] = key
    }

    fun hasNew(s: Source): Boolean {
        val key = newest[s.id]
        return !key.isNullOrEmpty() && key != seen[s.id]
    }

    fun markSeen(s: Source) {
        val key = newest[s.id]
        if (!key.isNullOrEmpty() && seen[s.id] != key) {
            seen[s.id] = key
            store.setSeen(s.id, key)
        }
    }

    fun setSubscribed(s: Source, on: Boolean) {
        store.setSubscribed(s, on)
        subscribedIds = if (on) subscribedIds + s.id else subscribedIds - s.id
    }

    fun changeTheme(mode: ThemeMode) {
        store.themeMode = mode
        themeMode = mode
    }

    fun addCustom(name: String, url: String) {
        val s = store.addCustom(name, url)
        allSources = allSources.filter { it.id != s.id } + s
        setSubscribed(s, true)
    }

    fun removeCustom(s: Source) {
        store.removeCustom(s)
        allSources = allSources.filter { it.id != s.id }
        subscribedIds = subscribedIds - s.id
        newest.remove(s.id)
        seen.remove(s.id)
    }
}
