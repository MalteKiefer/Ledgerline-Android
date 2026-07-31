package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.offline.AndroidConnectivity
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.data.ForceLogoutImpl
import de.ledgerline.app.domain.usecase.ForceLogout
import javax.inject.Singleton

/** Binds the network-state seam + the forced-logout use case for the finance app. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds
    @Singleton
    abstract fun connectivity(impl: AndroidConnectivity): Connectivity

    @Binds
    @Singleton
    abstract fun forceLogout(impl: ForceLogoutImpl): ForceLogout
}
