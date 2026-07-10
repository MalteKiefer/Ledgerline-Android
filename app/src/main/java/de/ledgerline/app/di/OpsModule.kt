package de.ledgerline.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.ops.AndroidServiceController
import de.ledgerline.app.core.ops.BackgroundOpsSetting
import de.ledgerline.app.core.ops.ServiceController
import de.ledgerline.app.core.ops.SettingsBackgroundOpsSetting
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OpsModule {
    @Binds
    @Singleton
    abstract fun serviceController(impl: AndroidServiceController): ServiceController

    @Binds
    @Singleton
    abstract fun backgroundOpsSetting(impl: SettingsBackgroundOpsSetting): BackgroundOpsSetting
}
