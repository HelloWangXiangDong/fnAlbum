package com.fnalbum.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * 图片请求共用一条 OkHttp 通道，拦截器动态注入当前账号的 AccessToken。
     * 缩略图接口只校验 AccessToken，不需要 authx 签名。
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val t = Api.tokenForImages()
                val request = if (t.isEmpty()) chain.request()
                else chain.request().newBuilder().header("AccessToken", t).build()
                chain.proceed(request)
            }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("photo_cache"))
                    .maxSizeBytes(400L * 1024 * 1024)
                    .build()
            }
            .crossfade(140)
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
