package de.ledgerline.app.data.gallery

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background camera-roll backup (runs while the app/device is locked). Reads the opt-in
 * [BackgroundCredStore] token into [SessionHolder] for the duration of the run, then restores the
 * locked state. No-op unless both "auto-backup" and "background backup" are on.
 */
@HiltWorker
class GalleryBackupWorker @AssistedInject constructor(
    @param:Assisted appContext: Context,
    @param:Assisted params: WorkerParameters,
    private val settings: SettingsStore,
    private val sessionHolder: SessionHolder,
    private val bgCred: BackgroundCredStore,
    private val backup: GalleryBackup,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settings.galleryBackupEnabled.first() || !settings.galleryBackupBackground.first()) return Result.success()
        val hadSession = sessionHolder.get() != null
        val session = sessionHolder.get() ?: bgCred.read() ?: return Result.success()
        if (!hadSession) sessionHolder.set(session)
        try {
            backup.runHeadless()
        } finally {
            if (!hadSession) sessionHolder.clear() // restore the locked state we borrowed
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "gallery-backup"

        fun schedule(context: Context, wifiOnly: Boolean) {
            val req = PeriodicWorkRequestBuilder<GalleryBackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
