package de.ledgerline.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.data.PairingRepository
import de.ledgerline.app.data.RememberedVaultStore
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.SettingsStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun keystoreSealer(): KeystoreSealer = KeystoreSealer()

    @Provides
    @Singleton
    fun sessionStore(@ApplicationContext ctx: Context, sealer: KeystoreSealer) = SessionStore(ctx, sealer)

    @Provides
    @Singleton
    fun pairingRepository() = PairingRepository()

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext ctx: Context) = SettingsStore(ctx)

    @Provides
    @Singleton
    fun rememberedVaultStore(@ApplicationContext ctx: Context) = RememberedVaultStore(ctx)
}
