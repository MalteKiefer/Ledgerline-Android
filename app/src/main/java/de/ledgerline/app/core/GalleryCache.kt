package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Gallery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryCache @Inject constructor() {
    private val _value = MutableStateFlow<Gallery?>(null)
    val value: StateFlow<Gallery?> = _value
    fun set(g: Gallery) { _value.value = g }
    fun clear() { _value.value = null }
}
