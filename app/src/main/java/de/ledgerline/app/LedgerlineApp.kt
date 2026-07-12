package de.ledgerline.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.offline.BackgroundSync
import org.osmdroid.config.Configuration

@HiltAndroidApp
class LedgerlineApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun backgroundSync(): BackgroundSync
    }

    override fun onCreate() {
        super.onCreate()
        // One-time init so PdfBox can load its bundled font resources for rendering.
        PDFBoxResourceLoader.init(applicationContext)
        // osmdroid requires a non-default user agent for OSM tile usage policy compliance.
        Configuration.getInstance().userAgentValue = packageName
        // Keep the offline cache fresh while the process lives (ZK-safe; see BackgroundSync).
        EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java).backgroundSync().start()
    }
}
