package `in`.melo.presentation.navigation

import androidx.navigation.NavController
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.presentation.viewmodel.PlayerViewModel

/** Opens a Spotify entity on the screen for its kind. Tracks start playing instead. */
fun NavController.openMusic(ui: PlayerViewModel, item: Music) {
    val id = ui.register(item)
    when (item.kind) {
        MusicKind.Album -> navigateSafely(Screen.AlbumDetail.createRoute(id))
        MusicKind.Artist -> navigateSafely(Screen.ArtistDetail.createRoute(id))
        MusicKind.Playlist -> navigateSafely(Screen.PlaylistDetail.createRoute(item.id))
        MusicKind.Radio -> navigateSafely(Screen.PlaylistDetail.createRoute(item.id))
        MusicKind.Track -> ui.playTrack(item)
    }
}
