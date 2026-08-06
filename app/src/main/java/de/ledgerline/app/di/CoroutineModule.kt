package de.ledgerline.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the process-lived application coroutine scope (never cancelled by design). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    /**
     * One app-lifetime scope for singletons that run background work for the whole
     * process (background sync, prefetch settings mirror). Injecting it
     * (instead of each singleton newing its own `CoroutineScope`) makes the lifetime
     * explicit and lets tests substitute a controlled scope.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
