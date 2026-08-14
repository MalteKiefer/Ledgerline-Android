package de.ledgerline.app

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class LedgerlineApp : Application(), androidx.work.Configuration.Provider {

    // Lets WorkManager construct @HiltWorker workers (the background gallery backup).
    @javax.inject.Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(workerFactory).build()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun serverReachability(): de.ledgerline.app.core.ServerReachability
    }

    override fun onCreate() {
        super.onCreate()
        val ep = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        // Check server reachability (GET /up) first + every 60s; drives the app's offline mode.
        ep.serverReachability().start()
    }
}
