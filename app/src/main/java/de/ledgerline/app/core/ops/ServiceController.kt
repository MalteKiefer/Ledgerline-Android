package de.ledgerline.app.core.ops

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indirection over the foreground [BackgroundOpService] so [OperationManager] can be
 * unit-tested on the JVM without touching Android. The production binding starts and
 * stops the real foreground service; tests supply a fake that records the calls.
 */
interface ServiceController {
    fun start()
    fun stop()
}

@Singleton
class AndroidServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServiceController {
    override fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BackgroundOpService::class.java),
        )
    }

    override fun stop() {
        context.stopService(Intent(context, BackgroundOpService::class.java))
    }
}
