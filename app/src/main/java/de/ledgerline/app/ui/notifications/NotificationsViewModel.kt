package de.ledgerline.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.remote.dto.NotificationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** In-app notifications: list + unread count, mark-one-read and mark-all-read (server-backed). */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val unread: Int = 0,
        val items: List<NotificationDto> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val r = accountRepository.notifications()
        _state.value = if (r == null) _state.value.copy(loading = false)
        else State(loading = false, unread = r.unread, items = r.items)
    }

    fun markRead(id: Long) = viewModelScope.launch {
        if (accountRepository.markNotificationRead(id)) {
            _state.value = _state.value.let { s ->
                s.copy(
                    items = s.items.map { if (it.id == id) it.copy(read = true) else it },
                    unread = (s.unread - if (s.items.any { it.id == id && !it.read }) 1 else 0).coerceAtLeast(0),
                )
            }
        }
    }

    fun markAllRead() = viewModelScope.launch {
        if (accountRepository.markAllNotificationsRead()) {
            _state.value = _state.value.let { s ->
                s.copy(items = s.items.map { it.copy(read = true) }, unread = 0)
            }
        }
    }
}
