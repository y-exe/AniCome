package net.yexe.anicome

import android.app.Application
import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.android.getKoin
import org.koin.core.context.startKoin

open class AniComeApp : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { ApiAccess.client.httpClient })) }
        .build()
    open val runtime: AppCoordinator get() = getKoin().get()
    override fun onCreate() {
        super.onCreate()
        startKoin { androidContext(this@AniComeApp); modules(appModule) }
        ApiAccess.injected = getKoin().get()
        AnimeCatalog.initialize(SettingsRepository.create(this, getKoin().get(), "anime-metadata", "anime-metadata-v2"))
        runtime.start()
        getKoin().get<ConvenienceRepository>().start()
        getKoin().get<AppLockRepository>().observeLifecycle(this)
        getKoin().get<CommentNotifications>().start()
        getKoin().get<UpdateRepository>().schedule(getKoin().get())
    }
}
val Context.aniComeApp: AppCoordinator get() = (applicationContext as AniComeApp).runtime
