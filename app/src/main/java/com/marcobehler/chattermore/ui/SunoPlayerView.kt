package com.marcobehler.chattermore.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.marcobehler.chattermore.R

class SunoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private lateinit var playButton: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var label: TextView
    private lateinit var subtitle: TextView
    private var controller: SunoPlaybackController? = null
    private var song: SunoSong? = null
    private val listener = SunoPlaybackController.Listener(::render)

    override fun onFinishInflate() {
        super.onFinishInflate()
        playButton = findViewById(R.id.sunoPlayButton)
        progress = findViewById(R.id.sunoProgress)
        label = findViewById(R.id.sunoLabel)
        subtitle = findViewById(R.id.sunoSubtitle)
    }

    fun bind(controller: SunoPlaybackController, song: SunoSong) {
        if (this.controller === controller && this.song == song) {
            render(controller.state)
            return
        }
        clear()
        this.controller = controller
        this.song = song
        controller.addListener(listener)
        controller.prefetch(song)
        val toggle = OnClickListener { controller.toggle(song) }
        setOnClickListener(toggle)
        playButton.setOnClickListener(toggle)
        render(controller.state)
    }

    fun clear() {
        controller?.removeListener(listener)
        controller = null
        song = null
        setOnClickListener(null)
        if (::playButton.isInitialized) playButton.setOnClickListener(null)
    }

    private fun render(state: SunoPlaybackState) {
        val boundSong = song ?: return
        val active = state.pageUrl == boundSong.pageUrl
        val resolvedTitle = controller?.titleFor(boundSong)
        val resolutionComplete = controller?.isResolutionComplete(boundSong) == true
        label.text = resolvedTitle ?: context.getString(
            if (resolutionComplete) R.string.suno_music_track else R.string.suno_loading_title,
        )
        when {
            active && state is SunoPlaybackState.Loading -> {
                progress.visibility = View.VISIBLE
                playButton.visibility = View.INVISIBLE
                subtitle.setText(R.string.suno_starting_playback)
                contentDescription = context.getString(R.string.suno_stop_loading)
            }
            active && state is SunoPlaybackState.Playing -> {
                progress.visibility = View.GONE
                playButton.visibility = View.VISIBLE
                playButton.setImageResource(R.drawable.ic_stop)
                label.text = state.title?.takeIf(String::isNotBlank)
                    ?: resolvedTitle
                    ?: context.getString(R.string.suno_music_track)
                subtitle.setText(R.string.suno_now_playing)
                contentDescription = context.getString(R.string.suno_stop_playback, label.text)
            }
            active && state is SunoPlaybackState.Error -> {
                progress.visibility = View.GONE
                playButton.visibility = View.VISIBLE
                playButton.setImageResource(R.drawable.ic_play)
                if (resolvedTitle == null) label.setText(R.string.suno_music_track)
                subtitle.setText(R.string.suno_playback_failed)
                contentDescription = context.getString(R.string.suno_retry_playback, label.text)
            }
            else -> {
                progress.visibility = View.GONE
                playButton.visibility = View.VISIBLE
                playButton.setImageResource(R.drawable.ic_play)
                subtitle.setText(
                    if (resolutionComplete) R.string.suno_music_label else R.string.suno_loading_details,
                )
                contentDescription = context.getString(R.string.suno_play_song, label.text)
            }
        }
    }
}
