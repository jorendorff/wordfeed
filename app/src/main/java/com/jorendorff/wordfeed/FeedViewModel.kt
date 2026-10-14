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
import kotlinx.coroutines.launch

/** How long a fetched page counts as fresh. */
private const val FRESH_MS = 60 * 60 * 1000L

/** How long unused cache entries are kept. */
private const val KEEP_MS = 30 * 24 * 60 * 60 * 1000L

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = HttpCache(app.cacheDir)

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
    private var generationStartedAt = System.currentTimeMillis()

    /** After the user taps Refresh, skip the freshness window (still revalidating cheaply). */
    private var bypassFreshness = false

    /** Source id -> key of the newest entry its reader page showed ("" if it failed). */
    private val newest = mutableStateMapOf<String, String>()

    /** Source ids whose reader pages are loading right now. */
    private val loading = mutableStateMapOf<String, Unit>()

    /** Source id -> key of the newest entry the user has looked at. */
    private val seen = mutableStateMapOf<String, String>()

    /** URL -> response, shared by all reader pages until the next refresh. Main thread only. */
    private val memo = HashMap<String, Deferred<Result<String>>>()

    val subscribedSources: List<Source>
        get() = allSources.filter { it.id in subscribedIds }

    init {
        for (s in allSources) {
            store.seen(s.id)?.let { seen[s.id] = it }
        }
        viewModelScope.launch(Dispatchers.IO) { http.prune(KEEP_MS) }
    }

    suspend fun fetch(url: String): Result<String> {
        val maxAge = if (bypassFreshness) 0L else FRESH_MS
        val deferred = memo.getOrPut(url) {
            viewModelScope.async(Dispatchers.IO) { runCatching { http.get(url, maxAge) } }
        }
        val result = deferred.await()
        // Don't keep failures around; the next attempt should retry.
        if (result.isFailure && memo[url] === deferred) memo.remove(url)
        return result
    }

    /**
     * A page failed to render from these responses. They may be junk that
     * came with a 200 (a bot check, an error page), so don't reuse them.
     */
    fun forget(urls: List<String>) {
        for (url in urls) memo.remove(url)
        viewModelScope.launch(Dispatchers.IO) { urls.forEach(http::remove) }
    }

    /** The Refresh button: reload what's visible, asking every site whether anything changed. */
    fun refresh() {
        bypassFreshness = true
        startGeneration()
    }

    /** On returning to the app: reload if what we're showing is more than an hour old. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - generationStartedAt > FRESH_MS) startGeneration()
    }

    private fun startGeneration() {
        memo.clear()
        generationStartedAt = System.currentTimeMillis()
        generation++
    }

    fun startedLoading(s: Source) {
        loading[s.id] = Unit
    }

    fun reportRendered(s: Source, key: String) {
        loading.remove(s.id)
        newest[s.id] = key
    }

    fun isLoading(s: Source): Boolean = s.id in loading

    fun newestKey(s: Source): String? = newest[s.id]

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
        loading.remove(s.id)
        seen.remove(s.id)
    }
}
