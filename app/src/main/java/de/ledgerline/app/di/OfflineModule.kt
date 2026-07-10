package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.offline.AndroidConnectivity
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.core.offline.ConstraintChecker
import de.ledgerline.app.core.offline.Constraints
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.OfflinePrefs
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
}
