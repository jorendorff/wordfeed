package com.jorendorff.wordfeed

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class SourceState(val content: SourceContent? = null, val loading: Boolean = false)

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var allSources by mutableStateOf(BUILTIN_SOURCES + store.customSources())
        private set

    var subscribedIds by mutableStateOf(
        allSources.filter { store.isSubscribed(it) }.map { it.id }.toSet()
    )
        private set

    val states = mutableStateMapOf<String, SourceState>()

    /** Source id -> guid of the newest item the user has looked at. */
    private val seen = mutableStateMapOf<String, String>()

    val subscribedSources: List<Source>
        get() = allSources.filter { it.id in subscribedIds }

    init {
        for (s in allSources) {
            store.seen(s.id)?.let { seen[s.id] = it }
        }
        refresh()
    }

    fun refresh() {
        for (s in subscribedSources) reload(s)
    }

    fun reload(s: Source) {
        val old = states[s.id]
        if (old?.loading == true) return
        states[s.id] = (old ?: SourceState()).copy(loading = true)
        viewModelScope.launch {
            val content = fetchSource(s)
            states[s.id] = SourceState(content = content, loading = false)
        }
    }

    fun setSubscribed(s: Source, on: Boolean) {
        store.setSubscribed(s, on)
        subscribedIds = if (on) subscribedIds + s.id else subscribedIds - s.id
        if (on && states[s.id]?.content == null) reload(s)
    }

    private fun newestGuid(s: Source): String? =
        (states[s.id]?.content as? SourceContent.Items)?.items?.firstOrNull()?.guid

    fun hasNew(s: Source): Boolean {
        val newest = newestGuid(s) ?: return false
        return seen[s.id] != newest
    }

    fun markSeen(s: Source) {
        val newest = newestGuid(s) ?: return
        if (seen[s.id] != newest) {
            seen[s.id] = newest
            store.setSeen(s.id, newest)
        }
    }

    fun addCustom(name: String, url: String) {
        val s = store.addCustom(name, url)
        allSources = allSources.filter { it.id != s.id } + s
        states.remove(s.id)
        setSubscribed(s, true)
    }

    fun removeCustom(s: Source) {
        store.removeCustom(s)
        allSources = allSources.filter { it.id != s.id }
        subscribedIds = subscribedIds - s.id
        states.remove(s.id)
        seen.remove(s.id)
    }
}
