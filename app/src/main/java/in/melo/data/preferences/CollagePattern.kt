package `in`.melo.data.preferences

import androidx.annotation.StringRes
import `in`.melo.R
enum class CollagePattern(
    val storageKey: String,
    @StringRes val labelRes: Int
) {
    COSMIC_SWIRL("cosmic_swirl", R.string.collage_cosmic_swirl),
    HONEYCOMB_GROOVE("honeycomb_groove", R.string.collage_honeycomb_groove),
    VINYL_STACK("vinyl_stack", R.string.collage_vinyl_stack),
    PIXEL_MOSAIC("pixel_mosaic", R.string.collage_pixel_mosaic),
    STARDUST_SCATTER("stardust_scatter", R.string.collage_stardust_scatter);

    companion object {
        val default: CollagePattern = COSMIC_SWIRL

        fun fromStorageKey(value: String?): CollagePattern {
            return entries.firstOrNull { it.storageKey == value } ?: default
        }
    }
}
