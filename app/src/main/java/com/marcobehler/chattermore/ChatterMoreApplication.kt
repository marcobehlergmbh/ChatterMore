package com.marcobehler.chattermore

import android.app.Application
import com.marcobehler.chattermore.background.BackgroundPollingScheduler
import com.marcobehler.chattermore.data.AttachmentDataSource
import com.marcobehler.chattermore.data.ConversationSummaryCache
import com.marcobehler.chattermore.network.MattermostApi
import com.marcobehler.chattermore.network.MattermostImageLoader
import com.marcobehler.chattermore.network.MattermostSocket
import com.marcobehler.chattermore.network.SunoAudioResolver
import com.marcobehler.chattermore.security.SecureConfigStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ChatterMoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BackgroundPollingScheduler.reconcile(this)
    }

    val container: AppContainer by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val uploadClient = client.newBuilder()
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
        AppContainer(
            context = this,
            api = MattermostApi(client, uploadClient),
            attachmentDataSource = AttachmentDataSource(this),
            socket = MattermostSocket(client),
            imageLoader = MattermostImageLoader(this, client),
            sunoResolver = SunoAudioResolver(client),
            configStore = SecureConfigStore(this),
            conversationCache = ConversationSummaryCache(this),
        )
    }
}

data class AppContainer(
    val context: android.content.Context,
    val api: MattermostApi,
    val attachmentDataSource: AttachmentDataSource,
    val socket: MattermostSocket,
    val imageLoader: MattermostImageLoader,
    val sunoResolver: SunoAudioResolver,
    val configStore: SecureConfigStore,
    val conversationCache: ConversationSummaryCache,
)
