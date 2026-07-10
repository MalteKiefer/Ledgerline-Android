package de.ledgerline.app.core.ops

import de.ledgerline.app.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam exposing only the one flag [OperationManager] needs from settings.
 * Lets the JVM unit test supply a fake without constructing a [SettingsStore]
 * (which requires an Android [android.content.Context]).
 */
interface BackgroundOpsSetting {
    val enabledFlow: Flow<Boolean>
}

@Singleton
class SettingsBackgroundOpsSetting @Inject constructor(
    private val settings: SettingsStore,
) : BackgroundOpsSetting {
    override val enabledFlow: Flow<Boolean> = settings.backgroundOpsEnabled
}
