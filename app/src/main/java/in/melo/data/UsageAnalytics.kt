package `in`.melo.data

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/** Controls Firebase Analytics with the privacy setting. The live count of open apps is separate. */
object UsageAnalytics {
    fun setEnabled(context: Context, enabled: Boolean) {
        val application = context.applicationContext
        analytics(application)?.setAnalyticsCollectionEnabled(enabled)
    }

    fun playbackStarted(context: Context, catalog: String) = log(context, "melo_playback_start") {
        putString("catalog", safeCatalog(catalog))
    }

    fun playerPlayingTime(context: Context, durationMs: Long, catalog: String) {
        if (durationMs <= 0L) return
        log(context, "melo_player_playing_time") {
            putLong("playing_ms", durationMs)
            putString("catalog", safeCatalog(catalog))
        }
    }

    fun buffering(context: Context, durationMs: Long, catalog: String) {
        if (durationMs <= 0L) return
        log(context, "melo_buffering") {
            putLong("buffering_ms", durationMs)
            putString("catalog", safeCatalog(catalog))
        }
    }

    fun playbackError(context: Context, catalog: String, category: String) = log(context, "melo_playback_error") {
        putString("catalog", safeCatalog(catalog))
        putString("category", category.takeIf { it in ERROR_CATEGORIES } ?: "other")
    }

    private inline fun log(context: Context, event: String, params: Bundle.() -> Unit) {
        val bundle = Bundle().apply(params)
        analytics(context.applicationContext)?.logEvent(event, bundle)
    }

    private fun analytics(context: Context): FirebaseAnalytics? = runCatching {
        if (FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseAnalytics.getInstance(context)
        } else null
    }.getOrNull()

    private fun safeCatalog(value: String): String = value.takeIf { it in CATALOGS } ?: "other"

    private val CATALOGS = setOf("spotify_catalog", "music_server", "local")
    private val ERROR_CATEGORIES = setOf("network", "decoder", "authorization", "unavailable", "other")
}
