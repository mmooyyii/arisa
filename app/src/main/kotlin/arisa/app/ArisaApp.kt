package arisa.app

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import arisa.app.data.LibraryRepository
import arisa.app.data.ProgressStore
import arisa.app.data.SessionStore
import java.util.concurrent.TimeUnit

class ArisaApp : Application(), SingletonImageLoader.Factory {
    lateinit var session: SessionStore
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var progress: ProgressStore
        private set

    /** 不跟着页面走的后台任务 (退出播放后同步进度). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 登录了才和网盘同步进度, 演示模式不同步. */
    fun syncProgress(onlyIfDirty: Boolean) {
        if (session.demo || !session.isLoggedIn) return
        scope.launch { if (onlyIfDirty) progress.syncIfDirty(session.client) else progress.sync(session.client) }
    }

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
            .memoryCache { MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(250)
            .build()

    companion object {
        lateinit var instance: ArisaApp
            private set
    }
}
