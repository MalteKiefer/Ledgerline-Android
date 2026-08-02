package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.offline.AndroidConnectivity
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.core.offline.ConstraintChecker
import de.ledgerline.app.core.offline.Constraints
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.OfflinePrefs
import de.ledgerline.app.core.offline.SyncableStore
import de.ledgerline.app.data.ExploreRepository
import de.ledgerline.app.data.FinanceRepository
import de.ledgerline.app.data.GalleryRepository
import de.ledgerline.app.data.HealthRepository
import de.ledgerline.app.data.PasswordsRepository
import de.ledgerline.app.data.PendingImportRepository
import de.ledgerline.app.data.WorkspaceRepository
import javax.inject.Singleton

/**
 * Binds the offline-infrastructure interfaces. [de.ledgerline.app.core.offline.StoreDiskCache]
 * and [de.ledgerline.app.core.offline.BlobDiskCache] are `@Singleton` with an
 * `@Inject` secondary constructor, so they need no explicit provider here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds
    @Singleton
    abstract fun connectivity(impl: AndroidConnectivity): Connectivity

    @Binds
    @Singleton
    abstract fun offlineFlags(impl: OfflinePrefs): OfflineFlags

    @Binds
    @Singleton
    abstract fun constraints(impl: ConstraintChecker): Constraints

    // ---- Offline write outbox: register each SyncableStore into the replay set. ----
    @Binds
    @IntoSet
    abstract fun passwordsSyncable(impl: PasswordsRepository): SyncableStore

    @Binds
    @IntoSet
    abstract fun healthSyncable(impl: HealthRepository): SyncableStore

    @Binds
    @IntoSet
    abstract fun exploreSyncable(impl: ExploreRepository): SyncableStore

    @Binds
    @IntoSet
    abstract fun workspaceSyncable(impl: WorkspaceRepository): SyncableStore

    @Binds
    @IntoSet
    abstract fun gallerySyncable(impl: GalleryRepository): SyncableStore

    @Binds
    @IntoSet
    abstract fun financeSyncable(impl: FinanceRepository): SyncableStore

    @Binds
    @IntoSet
    abstract fun pendingImportSyncable(impl: PendingImportRepository): SyncableStore
}
