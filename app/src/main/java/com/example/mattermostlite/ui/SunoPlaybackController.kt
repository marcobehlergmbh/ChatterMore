package com.example.mattermostlite.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.example.mattermostlite.network.ResolvedSunoAudio
import com.example.mattermostlite.network.SunoAudioResolver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class SunoPlaybackController(
    context: Context,
    private val resolver: SunoAudioResolver,
    private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(
            { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    stop()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        .build()
    private val listeners = mutableSetOf<Listener>()
    private val resolvedAudio = mutableMapOf<String, ResolvedSunoAudio>()
    private val resolutionJobs = mutableMapOf<String, Deferred<ResolvedSunoAudio>>()
    private val finishedResolutions = mutableSetOf<String>()
    private var resolveJob: Job? = null
    private var player: MediaPlayer? = null
    private var focusHeld = false
    private var generation = 0

    var state: SunoPlaybackState = SunoPlaybackState.Idle
        private set

    fun toggle(song: SunoSong) {
        if (state.pageUrl == song.pageUrl &&
            (state is SunoPlaybackState.Loading || state is SunoPlaybackState.Playing)
        ) {
            stop()
        } else {
            start(song)
        }
    }

    fun prefetch(song: SunoSong) {
        if (resolvedAudio[song.pageUrl] == null && song.pageUrl !in finishedResolutions) {
            resolutionFor(song)
        }
    }

    fun titleFor(song: SunoSong): String? = resolvedAudio[song.pageUrl]
        ?.title
        ?.takeIf(String::isNotBlank)

    fun isResolutionComplete(song: SunoSong): Boolean = song.pageUrl in finishedResolutions

    fun stop() {
        generation++
        resolveJob?.cancel()
        resolveJob = null
        player?.runCatching { stop() }
        player?.release()
        player = null
        abandonAudioFocus()
        updateState(SunoPlaybackState.Idle)
    }

    fun close() {
        stop()
        resolutionJobs.values.forEach { it.cancel() }
        resolutionJobs.clear()
        resolvedAudio.clear()
        finishedResolutions.clear()
        listeners.clear()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun start(song: SunoSong) {
        stop()
        val requestGeneration = ++generation
        updateState(SunoPlaybackState.Loading(song.pageUrl))
        resolveJob = scope.launch {
            runCatching {
                resolvedAudio[song.pageUrl] ?: resolutionFor(song).await()
            }
                .onSuccess { audio ->
                    if (generation == requestGeneration) {
                        resolveJob = null
                        prepare(song, audio, requestGeneration)
                    }
                }
                .onFailure {
                    if (generation == requestGeneration) fail(song.pageUrl)
                }
        }
    }

    private fun resolutionFor(song: SunoSong): Deferred<ResolvedSunoAudio> {
        resolutionJobs[song.pageUrl]?.let { return it }
        finishedResolutions -= song.pageUrl
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                resolver.resolve(song).also { audio ->
                    resolvedAudio[song.pageUrl] = audio
                }
            } finally {
                resolutionJobs.remove(song.pageUrl)
                finishedResolutions += song.pageUrl
                notifyListeners()
            }
        }
        resolutionJobs[song.pageUrl] = deferred
        deferred.start()
        return deferred
    }

    private fun prepare(song: SunoSong, audio: ResolvedSunoAudio, requestGeneration: Int) {
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) {
            fail(song.pageUrl)
            return
        }
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        runCatching {
            mediaPlayer.setAudioAttributes(audioAttributes)
            mediaPlayer.setDataSource(audio.audioUrl)
            mediaPlayer.setOnPreparedListener { prepared ->
                if (generation != requestGeneration || player !== prepared) {
                    prepared.release()
                    return@setOnPreparedListener
                }
                prepared.start()
                updateState(SunoPlaybackState.Playing(song.pageUrl, audio.title))
            }
            mediaPlayer.setOnCompletionListener { completed ->
                if (generation == requestGeneration && player === completed) stop()
            }
            mediaPlayer.setOnErrorListener { failed, _, _ ->
                if (generation == requestGeneration && player === failed) fail(song.pageUrl)
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure { fail(song.pageUrl) }
    }

    private fun fail(pageUrl: String) {
        resolveJob = null
        player?.release()
        player = null
        abandonAudioFocus()
        updateState(SunoPlaybackState.Error(pageUrl))
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
    }

    private fun updateState(value: SunoPlaybackState) {
        if (state == value) return
        state = value
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it.onStateChanged(state) }
    }

    fun interface Listener {
        fun onStateChanged(state: SunoPlaybackState)
    }
}

sealed class SunoPlaybackState(open val pageUrl: String?) {
    data object Idle : SunoPlaybackState(null)
    data class Loading(override val pageUrl: String) : SunoPlaybackState(pageUrl)
    data class Playing(
        override val pageUrl: String,
        val title: String?,
    ) : SunoPlaybackState(pageUrl)
    data class Error(override val pageUrl: String) : SunoPlaybackState(pageUrl)
}
