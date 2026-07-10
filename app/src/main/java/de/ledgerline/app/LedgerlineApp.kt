package de.ledgerline.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class LedgerlineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // One-time init so PdfBox can load its bundled font resources for rendering.
        PDFBoxResourceLoader.init(applicationContext)
        // osmdroid requires a non-default user agent for OSM tile usage policy compliance.
        Configuration.getInstance().userAgentValue = packageName
    }
}
