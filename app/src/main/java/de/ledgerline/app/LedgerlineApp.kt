package de.ledgerline.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.offline.BackgroundSync
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

@HiltAndroidApp
class LedgerlineApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun backgroundSync(): BackgroundSync
        fun serverReachability(): de.ledgerline.app.core.ServerReachability
    }

    override fun onCreate() {
        super.onCreate()
        // One-time init so PdfBox can load its bundled font resources for rendering.
        PDFBoxResourceLoader.init(applicationContext)

        // mapsforge must have its graphic factory created once before any MapView/Bitmap is
        // used. It is the app's sole map engine: offline vector (.map) rendering + the online
        // OSM tile fallback (descriptive User-Agent set on the tile source, no telemetry).
        AndroidGraphicFactory.createInstance(this)
        // Coarser pinch-zoom step: fewer intermediate re-renders per gesture → snappier, bigger
        // zoom jumps and less CPU (default ~1.05 re-renders very finely and feels sluggish).
        org.mapsforge.map.android.input.TouchGestureHandler.DELTA_SCALE = 1.2

        val ep = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        // Keep the offline cache fresh while the process lives (ZK-safe; see BackgroundSync).
        ep.backgroundSync().start()
        // Check server reachability (GET /up) first + every 60s; drives the app's offline mode.
        ep.serverReachability().start()
    }
}
