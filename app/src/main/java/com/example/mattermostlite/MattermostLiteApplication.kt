package com.example.mattermostlite

import android.app.Application
import com.example.mattermostlite.network.MattermostApi
import com.example.mattermostlite.network.MattermostImageLoader
import com.example.mattermostlite.network.MattermostSocket
import com.example.mattermostlite.network.SunoAudioResolver
import com.example.mattermostlite.security.SecureConfigStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MattermostLiteApplication : Application() {
    val container: AppContainer by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        AppContainer(
            api = MattermostApi(client),
            socket = MattermostSocket(client),
            imageLoader = MattermostImageLoader(this, client),
            sunoResolver = SunoAudioResolver(client),
            configStore = SecureConfigStore(this),
        )
    }
}

data class AppContainer(
    val api: MattermostApi,
    val socket: MattermostSocket,
    val imageLoader: MattermostImageLoader,
    val sunoResolver: SunoAudioResolver,
    val configStore: SecureConfigStore,
)
