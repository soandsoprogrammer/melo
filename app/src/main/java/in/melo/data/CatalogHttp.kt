package `in`.melo.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object CatalogHttp {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
}
