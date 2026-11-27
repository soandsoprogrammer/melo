package `in`.melo.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import `in`.melo.data.server.MusicServer
import okhttp3.OkHttpClient

@UnstableApi
internal object ServerAudioSource {
    fun factory(context: Context): OkHttpDataSource.Factory {
        val server = MusicServer.get(context)
        return OkHttpDataSource.Factory(OkHttpClient.Builder().addInterceptor(server::resolveMedia).build())
    }
}
