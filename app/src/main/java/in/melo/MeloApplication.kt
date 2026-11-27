package `in`.melo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import `in`.melo.data.NetworkMonitor
import `in`.melo.data.server.MusicServer
import `in`.melo.data.server.MusicServerUris
import `in`.melo.data.UsageAnalytics
import `in`.melo.data.preferences.SettingsStore
import java.io.File

class MeloApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        val analyticsEnabled = SettingsStore.get(this).settings.value.analyticsEnabled
        `in`.melo.data.MeloTelemetry.initialize(this)
        UsageAnalytics.setEnabled(this, analyticsEnabled)
        `in`.melo.data.diagnostics.DiagnosticLog.init(this)
        `in`.melo.data.diagnostics.DiagnosticLog.catchCrashes()
        `in`.melo.data.diagnostics.DiagnosticLog.recordPastExits(this)
        NetworkMonitor.start(this)
    }

    /** Covers stay on disk whatever the CDN's cache headers say: they never change for a given address. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder().directory(File(noBackupFilesDir, "image-cache")).maxSizeBytes(IMAGE_CACHE_BYTES).build()
        }
        .respectCacheHeaders(false)
        // Without network a cover comes from disk or not at all, at once rather than after a timeout.
        .okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                if (!NetworkMonitor.online.value) throw java.io.IOException("No connection")
                if (MusicServerUris.isRequest(chain.request().url)) MusicServer.get(this).resolveMedia(chain)
                else chain.proceed(chain.request())
            }.build()
        }
        .build()

    private companion object { const val IMAGE_CACHE_BYTES = 1024L * 1024 * 1024 }
}
