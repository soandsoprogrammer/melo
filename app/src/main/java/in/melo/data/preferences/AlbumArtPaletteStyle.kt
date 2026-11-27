package `in`.melo.data.preferences

import androidx.annotation.StringRes
import `in`.melo.R
enum class AlbumArtPaletteStyle(
    val storageKey: String,
    @StringRes val labelRes: Int
) {
    TONAL_SPOT("tonal_spot", R.string.palette_tonal_spot),
    VIBRANT("vibrant", R.string.palette_vibrant),
    EXPRESSIVE("expressive", R.string.palette_expressive),
    FRUIT_SALAD("fruit_salad", R.string.palette_fruit_salad);

    companion object {
        val default: AlbumArtPaletteStyle = TONAL_SPOT

        fun fromStorageKey(value: String?): AlbumArtPaletteStyle {
            return entries.firstOrNull { it.storageKey == value } ?: default
        }
    }
}
