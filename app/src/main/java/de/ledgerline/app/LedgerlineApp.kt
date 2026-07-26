package de.ledgerline.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import de.ledgerline.app.core.offline.BackgroundSync
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

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

        // MapLibre must be initialized once before any MapView is inflated. No token is
        // needed — we render a custom OSM raster style, not a Mapbox-hosted style.
        MapLibre.getInstance(applicationContext)
        // The OSM tile usage policy requires a descriptive User-Agent (osmdroid did this
        // via Configuration.userAgentValue = packageName). MapLibre fetches tiles through
        // its own OkHttp stack, so route that stack through an interceptor that stamps a
        // descriptive UA on every request. Dedicated client → no telemetry, no sharing
        // with the app's pinned API client.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("User-Agent", "de.ledgerline.app")
                        .build()
                    chain.proceed(req)
                }
                .build(),
        )
        // Keep the offline cache fresh while the process lives (ZK-safe; see BackgroundSync).
        EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java).backgroundSync().start()
    }
}
