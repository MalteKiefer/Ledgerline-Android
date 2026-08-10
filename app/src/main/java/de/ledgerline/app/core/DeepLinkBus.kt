package de.ledgerline.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A deep-link target the UI should navigate to once past the lock screen. */
enum class DeepLink { NOTIFICATIONS }

/**
 * Carries a deep-link intent (e.g. a tapped push notification) from [MainActivity] to the
 * composable shell. Buffered with replay=1 so a link fired while the app is locked survives until
 * the shell is composed and unlocked. The shell consumes and clears it after navigating.
 */
@Singleton
class DeepLinkBus @Inject constructor() {
    private val _links = MutableSharedFlow<DeepLink>(replay = 1, extraBufferCapacity = 1)
    val links: SharedFlow<DeepLink> = _links.asSharedFlow()

    fun emit(link: DeepLink) { _links.tryEmit(link) }
    fun clear() { _links.resetReplayCache() }
}
