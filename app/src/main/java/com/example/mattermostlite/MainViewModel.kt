package com.example.mattermostlite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mattermostlite.data.ChannelRepository
import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.ChannelNotFoundException
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedItem
import com.example.mattermostlite.model.FeedMutation
import com.example.mattermostlite.model.MattermostException
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.model.TeamNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface ScreenState {
    data object Loading : ScreenState

    data class Setup(
        val config: ServerConfig? = null,
        val connecting: Boolean = false,
        val error: String? = null,
    ) : ScreenState

    data class Channel(
        val title: String,
    ) : ScreenState
}

class MainViewModel(
    private val container: AppContainer,
    unsupportedMessage: String,
) : ViewModel() {
    private val repository = ChannelRepository(
        api = container.api,
        socket = container.socket,
        scope = viewModelScope,
        unsupportedMessage = unsupportedMessage,
    )
    private val _screen = MutableStateFlow<ScreenState>(ScreenState.Loading)
    private var benchmarkItems: List<FeedItem>? = null
    private var loadingOlder = false

    val screen: StateFlow<ScreenState> = _screen.asStateFlow()
    val mutations: SharedFlow<FeedMutation> = repository.mutations
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val refreshing: StateFlow<Boolean> = repository.refreshing

    init {
        if (BuildConfig.BENCHMARK_FIXTURES) {
            _screen.value = ScreenState.Setup()
        } else {
            val stored = container.configStore.load()
            if (stored == null) {
                _screen.value = ScreenState.Setup()
            } else {
                connect(stored, persist = false)
            }
        }
    }

    fun connect(config: ServerConfig, persist: Boolean = true) {
        if ((_screen.value as? ScreenState.Setup)?.connecting == true) return
        _screen.value = ScreenState.Setup(config = config, connecting = true)
        viewModelScope.launch {
            runCatching { repository.initialize(config) }
                .onSuccess { (channel, _) ->
                    val normalized = container.api.normalizedConfig(config)
                    if (persist) container.configStore.save(normalized)
                    _screen.value = ScreenState.Channel(
                        title = channel.displayName.ifBlank { channel.name },
                    )
                }
                .onFailure { error ->
                    if (!persist) container.configStore.clear()
                    _screen.value = ScreenState.Setup(
                        config = config.copy(token = ""),
                        error = userMessage(error),
                    )
                }
        }
    }

    fun clearConnection() {
        repository.close()
        container.configStore.clear()
        _screen.value = ScreenState.Setup()
    }

    fun setForeground(foreground: Boolean) {
        repository.setForeground(foreground)
    }

    fun enableBenchmarkFixture(count: Int) {
        if (!BuildConfig.BENCHMARK_FIXTURES) return
        benchmarkItems = (1..count.coerceIn(1, 2_000)).map { index ->
            FeedItem(
                key = "benchmark:$index",
                serverId = "benchmark-$index",
                pendingId = null,
                channelId = "benchmark",
                userId = "user-${index % 12}",
                author = "User ${index % 12}",
                message = if (index % 7 == 0) {
                    "A longer benchmark message at row $index that wraps across multiple lines to exercise text layout without Markdown or attachments."
                } else {
                    "Benchmark message $index"
                },
                createAt = 1_700_000_000_000L + index * 1_000L,
                updateAt = 1_700_000_000_000L + index * 1_000L,
                deliveryState = DeliveryState.SENT,
            )
        }
        _screen.value = ScreenState.Channel("Performance fixture")
    }

    suspend fun snapshot(): List<FeedItem> = benchmarkItems ?: repository.snapshot()

    fun loadOlder() {
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                repository.loadOlder()
            } catch (_: Exception) {
                // A later scroll can retry transient history failures.
            } finally {
                loadingOlder = false
            }
        }
    }

    fun refreshLatest() {
        viewModelScope.launch { runCatching { repository.refreshLatest() } }
    }

    fun send(message: String): Boolean {
        if (message.isBlank() || screen.value !is ScreenState.Channel) return false
        viewModelScope.launch { repository.send(message) }
        return true
    }

    fun retry(pendingId: String) {
        viewModelScope.launch { repository.retry(pendingId) }
    }

    override fun onCleared() {
        repository.close()
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is IllegalArgumentException -> error.message ?: "Invalid connection settings"
        is TeamNotFoundException -> error.message ?: "The team could not be found."
        is ChannelNotFoundException -> error.message ?: "The channel could not be found."
        is MattermostException -> when (error.statusCode) {
            401 -> "The personal access token is invalid or revoked."
            403 -> "This account cannot access or post to the channel."
            404 -> "The requested Mattermost resource could not be found."
            429 -> "The server rate limit was reached. Try again shortly."
            else -> error.message
        }
        is IOException -> "The server could not be reached. Check the URL, TLS certificate, and network."
        else -> error.message ?: "Connection failed"
    }

    class Factory(
        private val container: AppContainer,
        private val unsupportedMessage: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(container, unsupportedMessage) as T
    }
}
