package de.ledgerline.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LedgerlineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // One-time init so PdfBox can load its bundled font resources for rendering.
        PDFBoxResourceLoader.init(applicationContext)
    }
}
