package `in`.melo.data

import android.content.Context
import android.net.ConnectivityManager
import `in`.melo.data.preferences.AppSettings

/**
 * How good the stream may be, chosen separately for Wi-Fi and for mobile data. The lower rungs cap
 * the the audio provider format that is picked; High takes the best one offered; Lossless adds the switch
 * to an add-on's bit-exact copy of the same recording when one is found.
 */
enum class AudioQuality(val key: String) {
    /** The smallest stream, around 50 kbit/s. */
    LOW("low"),
    /** Up to about 128 kbit/s. */
    NORMAL("normal"),
    /** The best stream the audio provider offers. */
    HIGH("high"),
    /** High, upgraded to an add-on's lossless copy while it plays. */
    LOSSLESS("lossless");

    /** The highest bitrate a the audio provider format may have at this rung, or null for no limit. */
    val ceiling: Long? get() = when (this) {
        LOW -> 72_000L
        NORMAL -> 136_000L
        HIGH, LOSSLESS -> null
    }

    companion object {
        const val NORMAL_BANDWIDTH = 128_000L
        const val VERY_HIGH_BANDWIDTH = 256_000L

        val WifiDefault = LOSSLESS
        val MobileDefault = HIGH

        /** Reads a stored key; the older "auto" and "very_high" keys and unknown ones take [default]. */
        fun of(key: String, default: AudioQuality): AudioQuality = entries.firstOrNull { it.key == key } ?: default

        /** The rung that applies on the connection in use now. */
        fun current(context: Context, settings: AppSettings): AudioQuality {
            val metered = runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered
            }.getOrNull() ?: false
            return if (metered) of(settings.streamingQualityMobile, MobileDefault)
            else of(settings.streamingQualityWifi, WifiDefault)
        }
    }
}
