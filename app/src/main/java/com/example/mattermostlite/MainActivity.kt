package com.example.mattermostlite

import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedMutation
import com.example.mattermostlite.model.FeedImage
import com.example.mattermostlite.model.FeedFile
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.network.MattermostImageLoader
import com.example.mattermostlite.ui.FeedAdapter
import com.example.mattermostlite.ui.HistoryPrefetchPolicy
import com.example.mattermostlite.ui.DownloadFileName
import com.example.mattermostlite.ui.YouTubeVideo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val application = application as MattermostLiteApplication
        MainViewModel.Factory(
            application.container,
            getString(R.string.unsupported_message),
        )
    }

    private lateinit var setupPanel: View
    private lateinit var channelPanel: View
    private lateinit var serverUrl: EditText
    private lateinit var teamName: EditText
    private lateinit var channelName: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var connectButton: Button
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupError: TextView
    private lateinit var channelTitle: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var messageList: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var latestProgress: ProgressBar
    private lateinit var newMessagesButton: Button
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: FeedAdapter
    private var fullscreenImageDialog: Dialog? = null
    private var fullscreenVideoDialog: Dialog? = null
    private val imageLoader: MattermostImageLoader by lazy {
        (application as MattermostLiteApplication).container.imageLoader
    }

    private var loadedChannel = false
    private var lastBottomRefreshAt = 0L
    private var imeVisible = false
    private val refreshThreshold by lazy { 48f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        configureInsets()
        configureActions()
        configureFeed()
        if (BuildConfig.BENCHMARK_FIXTURES) {
            viewModel.enableBenchmarkFixture(intent.getIntExtra(EXTRA_BENCHMARK_MESSAGE_COUNT, 2_000))
        }
        collectState()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        if (loadedChannel && viewModel.screen.value is ScreenState.Channel) {
            lifecycleScope.launch {
                val keepBottom = isNearBottom()
                adapter.replace(viewModel.snapshot())
                if (keepBottom) messageList.post { scrollToBottom(smooth = false) }
            }
        }
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        fullscreenImageDialog?.dismiss()
        fullscreenImageDialog = null
        fullscreenVideoDialog?.dismiss()
        fullscreenVideoDialog = null
        super.onDestroy()
    }

    private fun bindViews() {
        setupPanel = findViewById(R.id.setupPanel)
        channelPanel = findViewById(R.id.channelPanel)
        serverUrl = findViewById(R.id.serverUrl)
        teamName = findViewById(R.id.teamName)
        channelName = findViewById(R.id.channelName)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        connectButton = findViewById(R.id.connectButton)
        setupProgress = findViewById(R.id.setupProgress)
        setupError = findViewById(R.id.setupError)
        channelTitle = findViewById(R.id.channelTitle)
        connectionStatus = findViewById(R.id.connectionStatus)
        messageList = findViewById(R.id.messageList)
        messageInput = findViewById(R.id.messageInput)
        latestProgress = findViewById(R.id.latestProgress)
        newMessagesButton = findViewById(R.id.newMessagesButton)
    }

    private fun configureInsets() {
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val keepBottom = ::adapter.isInitialized && isNearBottom()
            val keyboardOpened = keyboardVisible && !imeVisible
            imeVisible = keyboardVisible

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom),
            )
            if (keyboardOpened && keepBottom) {
                messageList.post { scrollToBottom(smooth = false) }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun configureActions() {
        connectButton.setOnClickListener {
            hideKeyboard()
            viewModel.connect(
                ServerConfig(
                    baseUrl = serverUrl.text.toString(),
                    teamName = teamName.text.toString(),
                    channelName = channelName.text.toString(),
                    username = username.text.toString(),
                    password = password.text.toString(),
                ),
            )
        }
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener {
            val message = messageInput.text.toString()
            if (viewModel.send(message)) messageInput.text.clear()
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.change_connection)
                .setMessage(R.string.clear_connection_prompt)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    adapter.replace(emptyList())
                    loadedChannel = false
                    viewModel.clearConnection()
                }
                .show()
        }
        newMessagesButton.setOnClickListener {
            scrollToBottom(smooth = false)
            newMessagesButton.isVisible = false
        }
    }

    private fun configureFeed() {
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = FeedAdapter(
            onRetry = viewModel::retry,
            onImageClick = ::showFullscreenImage,
            onFileClick = ::handleFileClick,
            onYouTubeClick = ::openYouTubeVideo,
            imageLoader = imageLoader,
        )
        messageList.layoutManager = layoutManager
        messageList.adapter = adapter
        messageList.itemAnimator = null
        messageList.setItemViewCacheSize(12)
        messageList.setHasFixedSize(false)

        var userGesture = false
        var userFling = false
        messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        userGesture = true
                        userFling = false
                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        if (userGesture) userFling = true
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (userFling && !recyclerView.canScrollVertically(1)) requestBottomRefresh()
                        userGesture = false
                        userFling = false
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (
                    dy < 0 &&
                    HistoryPrefetchPolicy.shouldPrefetch(
                        layoutManager.findFirstVisibleItemPosition(),
                        adapter.itemCount,
                    )
                ) {
                    viewModel.loadOlder()
                }
                if (!recyclerView.canScrollVertically(1)) newMessagesButton.isVisible = false
            }
        })

        var bottomStartY: Float? = null
        var bottomOverscroll = false
        messageList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bottomStartY = event.y.takeIf { !rv.canScrollVertically(1) }
                        bottomOverscroll = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val start = bottomStartY
                        if (start != null && start - event.y >= refreshThreshold) bottomOverscroll = true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (bottomOverscroll && !rv.canScrollVertically(1)) requestBottomRefresh()
                        bottomStartY = null
                        bottomOverscroll = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        bottomStartY = null
                        bottomOverscroll = false
                    }
                }
                return false
            }
        })
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.screen.collect(::renderScreen) }
                launch { viewModel.mutations.collect(::applyMutation) }
                launch { viewModel.connectionState.collect(::renderConnection) }
                launch { viewModel.refreshing.collect { latestProgress.isVisible = it } }
            }
        }
    }

    private fun renderScreen(state: ScreenState) {
        when (state) {
            ScreenState.Loading -> {
                setupPanel.isVisible = true
                channelPanel.isVisible = false
                setSetupEnabled(false)
                setupProgress.isVisible = true
            }
            is ScreenState.Setup -> {
                setupPanel.isVisible = true
                channelPanel.isVisible = false
                setSetupEnabled(!state.connecting)
                setupProgress.isVisible = state.connecting
                setupError.isVisible = !state.error.isNullOrBlank()
                setupError.text = state.error.orEmpty()
                state.config?.let(::populateSetup)
            }
            is ScreenState.Channel -> {
                setupPanel.isVisible = false
                channelPanel.isVisible = true
                channelTitle.text = state.title
                if (!loadedChannel) {
                    loadedChannel = true
                    lifecycleScope.launch {
                        adapter.replace(viewModel.snapshot())
                        messageList.post { scrollToBottom(smooth = false) }
                    }
                }
            }
        }
    }

    private fun renderConnection(state: ConnectionState) {
        val (text, color) = when (state) {
            ConnectionState.CONNECTING -> R.string.connecting to R.color.warning
            ConnectionState.CONNECTED -> R.string.connected to R.color.success
            ConnectionState.OFFLINE -> R.string.offline to R.color.error
        }
        connectionStatus.setText(text)
        connectionStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun applyMutation(mutation: FeedMutation) {
        val wasNearBottom = isNearBottom()
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(firstPosition)
        val firstOffset = firstView?.top ?: 0
        val firstKey = adapter.itemAt(firstPosition)?.key
        val previousNewest = adapter.itemAt(adapter.itemCount - 1)
        when (mutation) {
            is FeedMutation.Replace -> adapter.replace(mutation.items)
            is FeedMutation.Prepend -> adapter.prepend(mutation.items)
            is FeedMutation.Insert -> adapter.insert(mutation.index, mutation.item)
            is FeedMutation.Update -> adapter.update(mutation.previousKey, mutation.item)
            is FeedMutation.Remove -> adapter.remove(mutation.key)
        }

        when (mutation) {
            is FeedMutation.Insert -> {
                val ownPending = mutation.item.deliveryState == DeliveryState.OPTIMISTIC
                if (wasNearBottom || ownPending) {
                    messageList.post { scrollToBottom(smooth = false) }
                } else if (mutation.index <= firstPosition && firstPosition >= 0) {
                    layoutManager.scrollToPositionWithOffset(firstPosition + 1, firstOffset)
                } else {
                    newMessagesButton.isVisible = true
                }
            }
            is FeedMutation.Prepend -> {
                if (firstPosition >= 0) {
                    layoutManager.scrollToPositionWithOffset(
                        firstPosition + mutation.items.size,
                        firstOffset,
                    )
                }
            }
            is FeedMutation.Replace -> {
                val newNewest = mutation.items.lastOrNull()
                val ownPending = newNewest?.deliveryState == DeliveryState.OPTIMISTIC
                if (wasNearBottom || ownPending) {
                    messageList.post { scrollToBottom(false) }
                } else if (firstKey != null) {
                    val anchor = adapter.positionOf(firstKey)
                    if (anchor != RecyclerView.NO_POSITION) {
                        layoutManager.scrollToPositionWithOffset(anchor, firstOffset)
                    }
                }
                if (
                    !wasNearBottom &&
                    !ownPending &&
                    previousNewest != null &&
                    newNewest != null &&
                    newNewest.createAt > previousNewest.createAt
                ) {
                    newMessagesButton.isVisible = true
                }
            }
            else -> Unit
        }
    }

    private fun requestBottomRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBottomRefreshAt < 2_000) return
        lastBottomRefreshAt = now
        viewModel.refreshLatest()
    }

    private fun showFullscreenImage(image: FeedImage) {
        fullscreenImageDialog?.dismiss()
        val dialog = Dialog(this, R.style.Theme_MattermostLite_FullscreenImage).apply {
            setContentView(R.layout.dialog_fullscreen_image)
        }
        val imageView = dialog.findViewById<ImageView>(R.id.fullscreenImage).apply {
            contentDescription = image.name.ifBlank { getString(R.string.image_attachment) }
        }
        val progress = dialog.findViewById<ProgressBar>(R.id.fullscreenImageProgress)
        val error = dialog.findViewById<TextView>(R.id.fullscreenImageError)
        dialog.findViewById<ImageButton>(R.id.closeFullscreenImage).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        fullscreenImageDialog = dialog
        val request = imageLoader.loadFullscreen(image) { bitmap ->
            progress.isVisible = false
            if (bitmap == null) {
                error.isVisible = true
            } else {
                imageView.setImageBitmap(bitmap)
            }
        }
        dialog.setOnDismissListener {
            request.cancel()
            if (fullscreenImageDialog === dialog) fullscreenImageDialog = null
        }
    }

    private fun openYouTubeVideo(video: YouTubeVideo) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unable_to_open_link, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleFileClick(file: FeedFile) {
        if (file.isVideo) {
            showVideo(file)
        } else {
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(getString(R.string.download_file_prompt, file.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.download) { _, _ -> enqueueDownload(file) }
                .show()
        }
    }

    private fun showVideo(file: FeedFile) {
        val source = imageLoader.mediaSource(file.fileId) ?: return
        fullscreenVideoDialog?.dismiss()
        val dialog = Dialog(this, R.style.Theme_MattermostLite_FullscreenImage).apply {
            setContentView(R.layout.dialog_video)
        }
        val videoView = dialog.findViewById<VideoView>(R.id.fullscreenVideo)
        val progress = dialog.findViewById<ProgressBar>(R.id.fullscreenVideoProgress)
        val error = dialog.findViewById<TextView>(R.id.fullscreenVideoError)
        dialog.findViewById<TextView>(R.id.fullscreenVideoTitle).text = file.name
        dialog.findViewById<ImageButton>(R.id.closeFullscreenVideo).setOnClickListener {
            dialog.dismiss()
        }
        val controller = MediaController(this).apply { setAnchorView(videoView) }
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener {
            progress.isVisible = false
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            progress.isVisible = false
            error.isVisible = true
            true
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        fullscreenVideoDialog = dialog
        dialog.setOnDismissListener {
            videoView.stopPlayback()
            if (fullscreenVideoDialog === dialog) fullscreenVideoDialog = null
        }
        videoView.setVideoURI(source.uri, source.headers)
        videoView.requestFocus()
    }

    private fun enqueueDownload(file: FeedFile) {
        val source = imageLoader.mediaSource(file.fileId) ?: return
        val fileName = DownloadFileName.sanitize(file.name, file.fileId)
        val request = DownloadManager.Request(source.uri)
            .setTitle(file.name)
            .setDescription(getString(R.string.download_started, file.name))
            .setMimeType(file.mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        source.headers.forEach(request::addRequestHeader)
        try {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(
                this,
                getString(R.string.download_started, file.name),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (_: RuntimeException) {
            Toast.makeText(
                this,
                getString(R.string.download_failed, file.name),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun isNearBottom(): Boolean {
        if (adapter.itemCount == 0) return true
        return layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3
    }

    private fun scrollToBottom(smooth: Boolean) {
        val last = adapter.itemCount - 1
        if (last < 0) return
        if (smooth) messageList.smoothScrollToPosition(last) else messageList.scrollToPosition(last)
    }

    private fun populateSetup(config: ServerConfig) {
        if (serverUrl.text.isBlank()) serverUrl.setText(config.baseUrl)
        if (teamName.text.isBlank()) teamName.setText(config.teamName)
        if (channelName.text.isBlank()) channelName.setText(config.channelName)
        if (username.text.isBlank()) username.setText(config.username)
        if (password.text.isBlank()) password.setText(config.password)
    }

    private fun setSetupEnabled(enabled: Boolean) {
        serverUrl.isEnabled = enabled
        teamName.isEnabled = enabled
        channelName.isEnabled = enabled
        username.isEnabled = enabled
        password.isEnabled = enabled
        connectButton.isEnabled = enabled
    }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { manager.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private companion object {
        const val EXTRA_BENCHMARK_MESSAGE_COUNT = "benchmark_message_count"
    }
}
