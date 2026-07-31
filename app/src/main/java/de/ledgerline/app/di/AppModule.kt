package de.ledgerline.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.data.PairingRepository
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
    fun pairingRepository(@ApplicationContext ctx: Context) = PairingRepository(
        installId = de.ledgerline.app.core.InstallId.get(ctx),
        appVersion = de.ledgerline.app.BuildConfig.VERSION_NAME,
        osVersion = "Android " + android.os.Build.VERSION.RELEASE,
    )

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext ctx: Context) = SettingsStore(ctx)

    @Provides
    fun displayPrefsSink(settings: SettingsStore): de.ledgerline.app.core.prefs.DisplayPrefsSink = settings
}
