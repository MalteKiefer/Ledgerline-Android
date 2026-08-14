package de.ledgerline.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds content shared into the app via ACTION_SEND(_MULTIPLE) until the (biometrically unlocked)
 * shell can offer an upload. MainActivity copies the incoming URIs into cache immediately — while its
 * read grant is still live — and publishes the cached files here; the shell drains them once unlocked.
 */
@Singleton
class ShareInbox @Inject constructor() {
    data class Item(val file: File, val name: String, val mime: String?)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    fun set(list: List<Item>) { _items.value = list }
    fun clear() {
        _items.value.forEach { runCatching { it.file.delete() } }
        _items.value = emptyList()
    }
}
