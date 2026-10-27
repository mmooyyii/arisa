package quarkflix.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import quarkflix.app.data.LibraryRepository
import quarkflix.app.data.ProgressStore
import quarkflix.app.data.SessionStore
import java.util.concurrent.TimeUnit

class QuarkflixApp : Application(), SingletonImageLoader.Factory {
    lateinit var session: SessionStore
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var progress: ProgressStore
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = SessionStore(this, http)
        library = LibraryRepository(this, session, http)
        progress = ProgressStore(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(250)
            .build()

    companion object {
        lateinit var instance: QuarkflixApp
            private set
    }
}
