package com.marcobehler.chattermore

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marcobehler.chattermore.model.ConnectionState
import com.marcobehler.chattermore.model.ConversationMutation
import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedMutation
import com.marcobehler.chattermore.model.FeedImage
import com.marcobehler.chattermore.model.FeedFile
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.LinkPreview
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.ServerConfig
import com.marcobehler.chattermore.background.AppVisibility
import com.marcobehler.chattermore.background.BackgroundNotificationMode
import com.marcobehler.chattermore.background.BackgroundPollingWorker
import com.marcobehler.chattermore.data.AttachmentSelection
import com.marcobehler.chattermore.data.IncomingShare
import com.marcobehler.chattermore.data.IncomingShareParser
import com.marcobehler.chattermore.data.combineSharedDraftText
import com.marcobehler.chattermore.network.MattermostImageLoader
import com.marcobehler.chattermore.ui.DraftAttachmentAdapter
import com.marcobehler.chattermore.ui.ConversationAdapter
import com.marcobehler.chattermore.ui.FeedAdapter
import com.marcobehler.chattermore.ui.HistoryPrefetchPolicy
import com.marcobehler.chattermore.ui.DownloadFileName
import com.marcobehler.chattermore.ui.editFocusTopOffset
import com.marcobehler.chattermore.ui.nextEditFocusTop
import com.marcobehler.chattermore.ui.ImageZoomState
import com.marcobehler.chattermore.ui.MentionAutocomplete
import com.marcobehler.chattermore.ui.MentionSuggestionAdapter
import com.marcobehler.chattermore.ui.MessageScrollAnchor
import com.marcobehler.chattermore.ui.SunoPlaybackController
import com.marcobehler.chattermore.ui.YouTubeVideo
import com.marcobehler.chattermore.ui.VideoPlaybackErrorDetails
import com.marcobehler.chattermore.ui.VideoPlaybackErrorKind
import com.marcobehler.chattermore.ui.VideoPlaybackRestore
import com.marcobehler.chattermore.ui.ZoomableImageView
import kotlinx.coroutines.launch
import java.io.File

private data class EditFocusAnchor(
    val scrollAnchor: MessageScrollAnchor,
    val itemHeight: Int,
    var targetTop: Int,
)

private data class FullscreenImageRestore(
    val image: FeedImage,
    val zoomState: ImageZoomState,
)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val application = application as ChatterMoreApplication
        MainViewModel.Factory(
            application.container,
            getString(R.string.unsupported_message),
        )
    }

    private lateinit var setupPanel: View
    private lateinit var overviewPanel: View
    private lateinit var channelPanel: View
    private lateinit var serverUrl: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var connectButton: Button
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupError: TextView
    private lateinit var overviewTitle: TextView
    private lateinit var overviewConnectionStatus: TextView
    private lateinit var conversationList: RecyclerView
    private lateinit var overviewEmpty: TextView
    private lateinit var conversationBackButton: ImageButton
    private lateinit var channelTitle: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var messageList: RecyclerView
    private lateinit var editModeHeader: View
    private lateinit var editModeLabel: TextView
    private lateinit var cancelEditButton: ImageButton
    private lateinit var messageInput: EditText
    private lateinit var attachmentButton: ImageButton
    private lateinit var sendButton: ImageButton
    private lateinit var attachmentActions: View
    private lateinit var draftAttachmentList: RecyclerView
    private lateinit var latestProgress: ProgressBar
    private lateinit var newMessagesButton: Button
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: FeedAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var draftAttachmentAdapter: DraftAttachmentAdapter
    private lateinit var mentionSuggestionAdapter: MentionSuggestionAdapter
    private lateinit var mentionSuggestions: ListPopupWindow
    private var channelUsers: List<MattermostUser> = emptyList()
    private var fullscreenImageDialog: Dialog? = null
    private var activeFullscreenImage: FeedImage? = null
    private var activeFullscreenImageView: ZoomableImageView? = null
    private var pendingImageRestore: FullscreenImageRestore? = null
    private var fullscreenVideoDialog: Dialog? = null
    private var activeVideoFile: FeedFile? = null
    private var activeVideoPlayer: ExoPlayer? = null
    private var pendingVideoRestore: VideoPlaybackRestore? = null
    private var pendingCapture: AttachmentSelection? = null
    private var incomingShareDialog: AlertDialog? = null
    private var normalDraftAfterEdit: String? = null
    private val incomingShareParser = IncomingShareParser()
    private val attachmentBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setAttachmentActionsVisible(false)
        }
    }
    private val conversationBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            returnToOverview()
        }
    }
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris ->
        viewModel.addAttachments(uris.map(::AttachmentSelection))
    }
    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.addAttachments(uris.map(::AttachmentSelection))
    }
    private val photoCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> completeCapture(saved) }
    private val videoCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { saved -> completeCapture(saved) }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val toggle = pendingBackgroundSwitch
        pendingBackgroundSwitch = null
        if (granted) {
            if (!viewModel.setBackgroundPollingEnabled(true)) {
                toggle?.isChecked = false
                Toast.makeText(
                    this,
                    R.string.background_checks_need_connection,
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            toggle?.isChecked = false
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    private val imageLoader: MattermostImageLoader by lazy {
        (application as ChatterMoreApplication).container.imageLoader
    }
    private val sunoController: SunoPlaybackController by lazy {
        val container = (application as ChatterMoreApplication).container
        SunoPlaybackController(this, container.sunoResolver, lifecycleScope)
    }

    private var renderedConversationId: String? = null
    private var conversationLoading = false
    private var pendingScrollAnchor: MessageScrollAnchor? = null
    private var lastBottomRefreshAt = 0L
    private var imeVisible = false
    private var renderedEditingPostId: String? = null
    private var editFocusAnchor: EditFocusAnchor? = null
    private var anchoringEditSubmission = false
    private var pendingBackgroundSwitch: Switch? = null
    private var pendingNotificationPostId: String? = null
    private val releaseEditFocusAnchor = Runnable { editFocusAnchor = null }
    private val refreshThreshold by lazy { 48f * resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        onBackPressedDispatcher.addCallback(this, conversationBackCallback)
        onBackPressedDispatcher.addCallback(this, attachmentBackCallback)
        configureInsets()
        configureAttachmentComposer()
        configureMentionAutocomplete()
        configureActions()
        configureConversationOverview()
        configureFeed()
        pendingScrollAnchor = savedInstanceState?.scrollAnchor()
        pendingImageRestore = savedInstanceState?.fullscreenImageRestore()
        pendingVideoRestore = savedInstanceState?.videoPlaybackRestore()
        pendingNotificationPostId = intent.consumeNotificationPostId()
        pendingCapture = savedInstanceState
            ?.getString(STATE_CAPTURE_URI)
            ?.let(Uri::parse)
            ?.let { AttachmentSelection(it, temporary = true) }
        if (BuildConfig.BENCHMARK_FIXTURES) {
            viewModel.enableBenchmarkFixture(
                count = intent.getIntExtra(EXTRA_BENCHMARK_MESSAGE_COUNT, 2_000),
                mode = intent.getStringExtra(EXTRA_BENCHMARK_SCREEN),
            )
        }
        collectState()
        if (savedInstanceState == null) acceptIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.consumeNotificationPostId()?.let {
            pendingNotificationPostId = it
            val conversation = viewModel.screen.value as? ScreenState.Conversation
            if (conversation?.canNavigateBack == true) {
                viewModel.returnToOverview()
            } else {
                viewModel.refreshLatest()
            }
            messageList.post(::scrollToPendingNotification)
        }
        acceptIncomingShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.setForeground(true)
        viewModel.setForeground(true)
        if (
            renderedConversationId != null &&
            (viewModel.screen.value as? ScreenState.Conversation)?.loading == false
        ) {
            val anchor = pendingScrollAnchor ?: captureScrollAnchor()
            lifecycleScope.launch {
                adapter.replace(viewModel.snapshot())
                messageList.post { restoreScrollAnchor(anchor) }
            }
        }
    }

    override fun onStop() {
        pendingScrollAnchor = captureScrollAnchor() ?: pendingScrollAnchor
        pendingVideoRestore = captureVideoPlayback()
        dismissMentionSuggestions()
        fullscreenVideoDialog?.dismiss()
        sunoController.stop()
        viewModel.setForeground(false)
        AppVisibility.setForeground(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (captureScrollAnchor() ?: pendingScrollAnchor)?.let { anchor ->
            outState.putString(STATE_SCROLL_ITEM_KEY, anchor.itemKey)
            outState.putInt(STATE_SCROLL_POSITION, anchor.fallbackPosition)
            outState.putInt(STATE_SCROLL_OFFSET, anchor.topOffset)
        }
        captureFullscreenImage()?.writeTo(outState)
        captureVideoPlayback()?.writeTo(outState)
        pendingCapture?.uri?.toString()?.let { outState.putString(STATE_CAPTURE_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        messageList.removeCallbacks(releaseEditFocusAnchor)
        dismissMentionSuggestions()
        fullscreenImageDialog?.dismiss()
        fullscreenImageDialog = null
        fullscreenVideoDialog?.dismiss()
        fullscreenVideoDialog = null
        incomingShareDialog?.setOnDismissListener(null)
        incomingShareDialog?.dismiss()
        incomingShareDialog = null
        sunoController.close()
        super.onDestroy()
    }

    private fun bindViews() {
        setupPanel = findViewById(R.id.setupPanel)
        overviewPanel = findViewById(R.id.overviewPanel)
        channelPanel = findViewById(R.id.channelPanel)
        serverUrl = findViewById(R.id.serverUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        connectButton = findViewById(R.id.connectButton)
        setupProgress = findViewById(R.id.setupProgress)
        setupError = findViewById(R.id.setupError)
        overviewTitle = findViewById(R.id.overviewTitle)
        overviewConnectionStatus = findViewById(R.id.overviewConnectionStatus)
        conversationList = findViewById(R.id.conversationList)
        overviewEmpty = findViewById(R.id.overviewEmpty)
        conversationBackButton = findViewById(R.id.conversationBackButton)
        channelTitle = findViewById(R.id.channelTitle)
        connectionStatus = findViewById(R.id.connectionStatus)
        messageList = findViewById(R.id.messageList)
        editModeHeader = findViewById(R.id.editModeHeader)
        editModeLabel = findViewById(R.id.editModeLabel)
        cancelEditButton = findViewById(R.id.cancelEditButton)
        messageInput = findViewById(R.id.messageInput)
        attachmentButton = findViewById(R.id.attachmentButton)
        sendButton = findViewById(R.id.sendButton)
        attachmentActions = findViewById(R.id.attachmentActions)
        draftAttachmentList = findViewById(R.id.draftAttachmentList)
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
            if (editFocusAnchor != null) {
                scheduleEditFocusRelease()
            } else if (keyboardOpened && keepBottom) {
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
                    teamName = "",
                    channelName = "",
                    username = username.text.toString(),
                    password = password.text.toString(),
                ),
            )
        }
        sendButton.setOnClickListener {
            val message = messageInput.text.toString()
            if (viewModel.messageEdit.value == null) {
                if (viewModel.send(message)) messageInput.text.clear()
            } else {
                submitMessageEdit(message)
            }
        }
        messageInput.doAfterTextChanged {
            viewModel.updateEditDraft(it?.toString().orEmpty())
            updateComposerButtons()
            renderMentionSuggestions()
        }
        cancelEditButton.setOnClickListener { viewModel.cancelEdit() }
        conversationBackButton.setOnClickListener { returnToOverview() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            showConnectionSettings()
        }
        findViewById<ImageButton>(R.id.overviewSettingsButton).setOnClickListener {
            showConnectionSettings()
        }
        findViewById<Button>(R.id.setupPrivacyPolicyButton).setOnClickListener {
            openPrivacyPolicy()
        }
        newMessagesButton.setOnClickListener {
            scrollToBottom(smooth = false)
            newMessagesButton.isVisible = false
        }
    }

    private fun configureConversationOverview() {
        conversationAdapter = ConversationAdapter(imageLoader, viewModel::openConversation)
        conversationList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
            setHasFixedSize(true)
        }
    }

    @Suppress("DEPRECATION")
    private fun showConnectionSettings() {
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val settings = viewModel.backgroundPollingSettings()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing / 2, spacing, 0)
        }
        val backgroundToggle = Switch(this).apply {
            text = getString(R.string.background_message_checks)
            isChecked = settings.enabled
        }
        val explanation = TextView(this).apply {
            setText(R.string.background_message_checks_explanation)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, 0, 0, spacing)
        }
        val notifyFor = TextView(this).apply {
            setText(R.string.notify_for)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val mentions = RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.mentions_only)
        }
        val allMessages = RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.all_new_messages)
        }
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(mentions)
            addView(allMessages)
            check(
                if (settings.mode == BackgroundNotificationMode.MENTIONS) {
                    mentions.id
                } else {
                    allMessages.id
                },
            )
        }
        val preview = CheckBox(this).apply {
            setText(R.string.show_message_preview)
            isChecked = settings.showPreview
        }
        val lockScreenTitle = TextView(this).apply {
            setText(R.string.lock_screen_notifications)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, spacing / 2, 0, 0)
        }
        val lockScreenExplanation = TextView(this).apply {
            setText(R.string.lock_screen_notifications_explanation)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val openNotificationSettings = Button(this).apply {
            setText(R.string.open_android_notification_settings)
            setOnClickListener { openBackgroundNotificationSettings() }
        }
        val openPrivacyPolicy = Button(this).apply {
            setText(R.string.privacy_policy)
            setOnClickListener { openPrivacyPolicy() }
        }
        content.addView(backgroundToggle)
        content.addView(explanation)
        content.addView(notifyFor)
        content.addView(modeGroup)
        content.addView(preview)
        content.addView(lockScreenTitle)
        content.addView(lockScreenExplanation)
        content.addView(openNotificationSettings)
        content.addView(openPrivacyPolicy)

        fun updatePreferenceControls(enabled: Boolean) {
            notifyFor.isEnabled = enabled
            mentions.isEnabled = enabled
            allMessages.isEnabled = enabled
            preview.isEnabled = enabled
        }
        updatePreferenceControls(settings.enabled)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            viewModel.setBackgroundPollingPreferences(
                mode = if (checkedId == allMessages.id) {
                    BackgroundNotificationMode.ALL
                } else {
                    BackgroundNotificationMode.MENTIONS
                },
                showPreview = preview.isChecked,
            )
        }
        preview.setOnCheckedChangeListener { _, checked ->
            viewModel.setBackgroundPollingPreferences(
                mode = if (allMessages.isChecked) {
                    BackgroundNotificationMode.ALL
                } else {
                    BackgroundNotificationMode.MENTIONS
                },
                showPreview = checked,
            )
        }
        backgroundToggle.setOnCheckedChangeListener { button, checked ->
            updatePreferenceControls(checked)
            if (!checked) {
                viewModel.setBackgroundPollingEnabled(false)
                return@setOnCheckedChangeListener
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pendingBackgroundSwitch = button as Switch
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!viewModel.setBackgroundPollingEnabled(true)) {
                button.isChecked = false
                Toast.makeText(
                    this,
                    R.string.background_checks_need_connection,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.connection_settings)
            .setView(content)
            .setNegativeButton(R.string.change_connection) { _, _ -> confirmClearConnection() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.privacy_policy_url)),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.unable_to_open_privacy_policy,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openBackgroundNotificationSettings() {
        BackgroundPollingWorker.createNotificationChannel(this)
        val channelSettings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, BackgroundPollingWorker.CHANNEL_ID)
        }
        try {
            startActivity(channelSettings)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                },
            )
        }
    }

    private fun confirmClearConnection() {
        AlertDialog.Builder(this)
            .setTitle(R.string.change_connection)
            .setMessage(R.string.clear_connection_prompt)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                adapter.replace(emptyList())
                conversationAdapter.replace(emptyList())
                renderedConversationId = null
                password.text.clear()
                viewModel.clearConnection()
            }
            .show()
    }

    private fun configureAttachmentComposer() {
        draftAttachmentAdapter = DraftAttachmentAdapter(
            context = this,
            scope = lifecycleScope,
            onRetry = viewModel::retryAttachment,
            onRemove = viewModel::removeAttachment,
        )
        draftAttachmentList.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false,
        )
        draftAttachmentList.adapter = draftAttachmentAdapter
        draftAttachmentList.itemAnimator = null

        attachmentButton.setOnClickListener {
            setAttachmentActionsVisible(!attachmentActions.isVisible)
        }
        findViewById<View>(R.id.galleryAction).setOnClickListener {
            setAttachmentActionsVisible(false)
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }
        findViewById<View>(R.id.fileAction).setOnClickListener {
            setAttachmentActionsVisible(false)
            fileLauncher.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.cameraAction).setOnClickListener {
            setAttachmentActionsVisible(false)
            AlertDialog.Builder(this)
                .setItems(arrayOf(getString(R.string.take_photo), getString(R.string.record_video))) { _, which ->
                    launchCapture(video = which == 1)
                }
                .show()
        }
    }

    private fun configureMentionAutocomplete() {
        mentionSuggestionAdapter = MentionSuggestionAdapter(this)
        mentionSuggestions = ListPopupWindow(this).apply {
            anchorView = messageInput
            setAdapter(mentionSuggestionAdapter)
            setBackgroundDrawable(
                ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.mention_suggestions_background,
                ),
            )
            setDropDownGravity(Gravity.START)
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            isModal = false
            setOnItemClickListener { _, _, position, _ ->
                insertMention(mentionSuggestionAdapter.getItem(position))
            }
        }
        messageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) renderMentionSuggestions() else dismissMentionSuggestions()
        }
    }

    private fun renderMentionSuggestions() {
        if (
            !::mentionSuggestions.isInitialized ||
            !messageInput.hasFocus() ||
            messageInput.selectionStart != messageInput.selectionEnd
        ) {
            dismissMentionSuggestions()
            return
        }
        val mention = MentionAutocomplete.activeMention(
            messageInput.text,
            messageInput.selectionStart,
        )
        if (mention == null) {
            dismissMentionSuggestions()
            return
        }
        val suggestions = MentionAutocomplete.suggestions(channelUsers, mention.query)
        if (suggestions.isEmpty()) {
            dismissMentionSuggestions()
            return
        }
        mentionSuggestionAdapter.submit(suggestions)
        val density = resources.displayMetrics.density
        mentionSuggestions.width = messageInput.width
        mentionSuggestions.height = (MENTION_ROW_HEIGHT_DP * density * minOf(
            suggestions.size,
            MAX_VISIBLE_MENTION_SUGGESTIONS,
        )).toInt()
        if (messageInput.width == 0) {
            messageInput.post(::renderMentionSuggestions)
        } else {
            mentionSuggestions.show()
        }
    }

    private fun insertMention(user: MattermostUser) {
        val selection = messageInput.selectionStart
        val mention = MentionAutocomplete.activeMention(messageInput.text, selection)
        if (mention == null) {
            dismissMentionSuggestions()
            return
        }
        val insertion = MentionAutocomplete.insert(messageInput.text, mention, user.username)
        messageInput.setText(insertion.text)
        messageInput.setSelection(insertion.cursor)
        messageInput.requestFocus()
        dismissMentionSuggestions()
    }

    private fun dismissMentionSuggestions() {
        if (::mentionSuggestions.isInitialized) mentionSuggestions.dismiss()
    }

    private fun setAttachmentActionsVisible(visible: Boolean) {
        if (visible == attachmentActions.isVisible) return
        attachmentActions.animate().cancel()
        attachmentBackCallback.isEnabled = visible
        if (visible) {
            attachmentActions.alpha = 0f
            attachmentActions.translationY = attachmentActions.resources.displayMetrics.density * 20f
            attachmentActions.isVisible = true
            attachmentActions.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ATTACHMENT_ANIMATION_MS)
                .start()
        } else {
            attachmentActions.animate()
                .alpha(0f)
                .translationY(attachmentActions.resources.displayMetrics.density * 20f)
                .setDuration(ATTACHMENT_ANIMATION_MS)
                .withEndAction {
                    attachmentActions.isVisible = false
                    attachmentActions.alpha = 1f
                    attachmentActions.translationY = 0f
                }
                .start()
        }
    }

    private fun launchCapture(video: Boolean) {
        val file = runCatching {
            val captureDirectory = File(cacheDir, "captures").apply { mkdirs() }
            File.createTempFile(
                if (video) "video-" else "photo-",
                if (video) ".mp4" else ".jpg",
                captureDirectory,
            )
        }.getOrElse {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val selection = AttachmentSelection(
            uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file),
            temporary = true,
        )
        pendingCapture = selection
        try {
            if (video) videoCaptureLauncher.launch(selection.uri) else photoCaptureLauncher.launch(selection.uri)
        } catch (_: ActivityNotFoundException) {
            pendingCapture = null
            file.delete()
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun completeCapture(saved: Boolean) {
        val selection = pendingCapture ?: return
        pendingCapture = null
        if (saved) {
            viewModel.addAttachments(listOf(selection))
        } else {
            runCatching { contentResolver.delete(selection.uri, null, null) }
        }
    }

    private fun configureFeed() {
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = FeedAdapter(
            onRetry = viewModel::retry,
            onUserClick = ::openDirectMessage,
            onImageClick = ::showFullscreenImage,
            onFileClick = ::handleFileClick,
            onYouTubeClick = ::openYouTubeVideo,
            onLinkPreviewClick = ::openLinkPreview,
            onMessageLongPress = ::beginMessageEdit,
            imageLoader = imageLoader,
            sunoController = sunoController,
        )
        messageList.layoutManager = layoutManager
        messageList.adapter = adapter
        messageList.itemAnimator = null
        messageList.setItemViewCacheSize(12)
        messageList.setHasFixedSize(false)
        messageList.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            correctEditFocusAfterLayout()
        }

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
                val atLatest = !recyclerView.canScrollVertically(1)
                viewModel.setConversationAtLatest(atLatest)
                if (atLatest) newMessagesButton.isVisible = false
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
                launch { viewModel.conversationMutations.collect(conversationAdapter::apply) }
                launch { viewModel.connectionState.collect(::renderConnection) }
                launch { viewModel.userPresence.collect(adapter::updatePresence) }
                launch { viewModel.mentionContext.collect(adapter::updateMentionContext) }
                launch {
                    viewModel.channelUsers.collect {
                        channelUsers = it
                        renderMentionSuggestions()
                    }
                }
                launch {
                    viewModel.refreshing.collect {
                        latestProgress.isVisible = it || conversationLoading
                    }
                }
                launch { viewModel.messageEdit.collect(::renderMessageEdit) }
                launch { viewModel.composer.collect(::renderComposer) }
                launch {
                    viewModel.incomingShares.collect { handleNextIncomingShare() }
                }
                launch {
                    viewModel.composerMessages.collect {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun beginMessageEdit(item: FeedItem): Boolean {
        val candidateAnchor = captureItemAnchor(item.key)
        editFocusAnchor = candidateAnchor
        messageList.removeCallbacks(releaseEditFocusAnchor)
        if (!viewModel.beginEdit(item)) {
            editFocusAnchor = null
            return false
        }
        scheduleEditFocusRelease()
        return true
    }

    private fun openDirectMessage(userId: String, title: String) {
        val current = viewModel.screen.value as? ScreenState.Conversation ?: return
        if (!current.loading && current.peerUserId == userId) return
        dismissMentionSuggestions()
        setAttachmentActionsVisible(false)
        pendingScrollAnchor = null
        normalDraftAfterEdit = null
        messageInput.text.clear()
        viewModel.openDirectMessage(userId, title)
    }

    private fun returnToOverview() {
        dismissMentionSuggestions()
        setAttachmentActionsVisible(false)
        pendingScrollAnchor = null
        normalDraftAfterEdit = null
        messageInput.text.clear()
        viewModel.returnToOverview()
    }

    private fun submitMessageEdit(message: String) {
        val edit = viewModel.messageEdit.value ?: return
        val submissionAnchor = captureServerPostAnchor(edit.postId)
        if (submissionAnchor != null) {
            editFocusAnchor = submissionAnchor
            anchoringEditSubmission = true
            messageList.removeCallbacks(releaseEditFocusAnchor)
        }
        if (!viewModel.submitEdit(message) && submissionAnchor != null) {
            anchoringEditSubmission = false
            scheduleEditFocusRelease()
        }
    }

    private fun renderMessageEdit(state: MessageEditState?) {
        val previousPostId = renderedEditingPostId
        editModeHeader.isVisible = state != null

        if (state != null) {
            setAttachmentActionsVisible(false)
            attachmentButton.isVisible = false
            if (previousPostId != state.postId) {
                messageInput.setText(state.draftMessage)
                scheduleEditFocusRelease()
                focusMessageInputForEdit()
            }
            editModeLabel.setText(
                when {
                    state.submitting -> R.string.updating_message
                    state.failed -> R.string.edit_message_failed
                    else -> R.string.editing_message
                },
            )
            editModeLabel.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (state.failed) R.color.error else R.color.chattermore_blue,
                ),
            )
            messageInput.isEnabled = true
            sendButton.isEnabled = !state.submitting
            cancelEditButton.isEnabled = !state.submitting
            if (state.failed && anchoringEditSubmission) {
                anchoringEditSubmission = false
                scheduleEditFocusRelease()
            }
        } else {
            attachmentButton.isVisible = true
            if (previousPostId != null) {
                val restoredDraft = normalDraftAfterEdit
                normalDraftAfterEdit = null
                if (restoredDraft == null) {
                    messageInput.text.clear()
                } else {
                    messageInput.setText(restoredDraft)
                    messageInput.setSelection(messageInput.text.length)
                }
            }
            messageInput.isEnabled = true
            updateComposerButtons()
            cancelEditButton.isEnabled = true
            if (previousPostId != null && anchoringEditSubmission && editFocusAnchor != null) {
                anchoringEditSubmission = false
                messageList.doOnPreDraw {
                    correctEditFocusAfterLayout()
                    scheduleEditFocusRelease()
                }
            } else {
                editFocusAnchor = null
                messageList.removeCallbacks(releaseEditFocusAnchor)
            }
        }
        renderedEditingPostId = state?.postId
        messageInput.post(::handleNextIncomingShare)
    }

    private fun renderComposer(state: ComposerState) {
        draftAttachmentAdapter.submit(state.attachments)
        draftAttachmentList.isVisible = state.attachments.isNotEmpty()
        updateComposerButtons()
    }

    private fun updateComposerButtons() {
        val conversation = viewModel.screen.value as? ScreenState.Conversation
        if (conversation == null || conversation.loading) {
            sendButton.isEnabled = false
            attachmentButton.isEnabled = false
            return
        }
        val edit = viewModel.messageEdit.value
        if (edit != null) {
            sendButton.isEnabled = !edit.submitting
            attachmentButton.isEnabled = false
            return
        }
        val state = viewModel.composer.value
        attachmentButton.isEnabled = state.capabilities.enabled
        sendButton.isEnabled = !state.hasBlockingAttachments &&
            (messageInput.text.isNotBlank() || state.readyFiles.isNotEmpty())
    }

    private fun renderScreen(state: ScreenState) {
        when (state) {
            ScreenState.Loading -> {
                conversationLoading = false
                conversationBackCallback.isEnabled = false
                dismissMentionSuggestions()
                setupPanel.isVisible = true
                overviewPanel.isVisible = false
                channelPanel.isVisible = false
                setSetupEnabled(false)
                setupProgress.isVisible = true
            }
            is ScreenState.Setup -> {
                conversationLoading = false
                conversationBackCallback.isEnabled = false
                dismissMentionSuggestions()
                setupPanel.isVisible = true
                overviewPanel.isVisible = false
                channelPanel.isVisible = false
                setSetupEnabled(!state.connecting)
                setupProgress.isVisible = state.connecting
                setupError.isVisible = !state.error.isNullOrBlank()
                setupError.text = state.error.orEmpty()
                state.config?.let(::populateSetup)
            }
            is ScreenState.Overview -> {
                conversationLoading = false
                conversationBackCallback.isEnabled = false
                dismissMentionSuggestions()
                setupPanel.isVisible = false
                overviewPanel.isVisible = true
                channelPanel.isVisible = false
                overviewTitle.text = state.teamTitle
                conversationAdapter.replace(state.conversations)
                overviewEmpty.isVisible = state.conversations.isEmpty()
            }
            is ScreenState.Conversation -> {
                setupPanel.isVisible = false
                overviewPanel.isVisible = false
                channelPanel.isVisible = true
                channelTitle.text = state.title
                conversationLoading = state.loading
                conversationBackButton.isVisible = state.canNavigateBack
                conversationBackButton.isEnabled = state.canNavigateBack
                conversationBackCallback.isEnabled = state.canNavigateBack
                messageList.isVisible = !state.loading
                latestProgress.isVisible = state.loading || viewModel.refreshing.value
                updateComposerButtons()
                if (!state.loading && renderedConversationId != state.id) {
                    renderedConversationId = state.id
                    val anchor = pendingScrollAnchor
                    lifecycleScope.launch {
                        adapter.replace(viewModel.snapshot())
                        messageList.post {
                            if (anchor == null) {
                                scrollToBottom(smooth = false)
                            } else {
                                restoreScrollAnchor(anchor)
                            }
                            scrollToPendingNotification()
                        }
                    }
                }
                restorePendingImage()
                restorePendingVideo()
                handleNextIncomingShare()
            }
        }
    }

    private fun acceptIncomingShareIntent(shareIntent: Intent) {
        if (shareIntent.action != Intent.ACTION_SEND && shareIntent.action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }
        incomingShareParser.parse(shareIntent)?.let(viewModel::enqueueIncomingShare)
        shareIntent.action = null
    }

    private fun handleNextIncomingShare() {
        val conversation = viewModel.screen.value as? ScreenState.Conversation
        if (conversation == null || conversation.loading || incomingShareDialog != null) return
        if (viewModel.messageEdit.value?.submitting == true) return
        val share = viewModel.incomingShares.value.firstOrNull() ?: return
        val composer = viewModel.composer.value
        val hasDraft = messageInput.text.isNotBlank() ||
            viewModel.messageEdit.value != null ||
            composer.attachments.isNotEmpty() ||
            composer.resolvingCount > 0
        if (!hasDraft) {
            applyIncomingShare(share, replaceDraft = false)
            return
        }

        var resolved = false
        incomingShareDialog = AlertDialog.Builder(this)
            .setTitle(R.string.shared_content_title)
            .setMessage(R.string.shared_content_conflict)
            .setPositiveButton(R.string.add_shared_content) { _, _ ->
                resolved = true
                applyIncomingShare(share, replaceDraft = false)
            }
            .setNeutralButton(R.string.replace_shared_content) { _, _ ->
                resolved = true
                applyIncomingShare(share, replaceDraft = true)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                resolved = true
                viewModel.consumeIncomingShare(share.id)
            }
            .setOnCancelListener {
                if (!resolved) viewModel.consumeIncomingShare(share.id)
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    incomingShareDialog = null
                    messageInput.post(::handleNextIncomingShare)
                }
                dialog.show()
            }
    }

    private fun applyIncomingShare(share: IncomingShare, replaceDraft: Boolean) {
        if (!viewModel.consumeIncomingShare(share.id)) return
        setAttachmentActionsVisible(false)

        val currentDraft = messageInput.text.toString()
        val nextDraft = if (replaceDraft) {
            share.text
        } else {
            combineSharedDraftText(currentDraft, share.text)
        }
        if (replaceDraft) viewModel.discardAttachments()
        if (viewModel.messageEdit.value != null) {
            if (renderedEditingPostId != null) normalDraftAfterEdit = nextDraft
            viewModel.cancelEdit()
        }
        messageInput.setText(nextDraft)
        messageInput.setSelection(messageInput.text.length)
        if (share.attachments.isNotEmpty()) viewModel.addAttachments(share.attachments)
        focusMessageInputForShare()
    }

    private fun renderConnection(state: ConnectionState) {
        val (text, color) = when (state) {
            ConnectionState.CONNECTING -> R.string.connecting to R.color.warning
            ConnectionState.CONNECTED -> R.string.connected to R.color.success
            ConnectionState.OFFLINE -> R.string.offline to R.color.error
        }
        connectionStatus.setText(text)
        connectionStatus.setTextColor(ContextCompat.getColor(this, color))
        overviewConnectionStatus.setText(text)
        overviewConnectionStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun applyMutation(mutation: FeedMutation) {
        if (mutation is FeedMutation.Replace && mutation.conversationId != null) {
            adapter.replace(mutation.items)
            renderedConversationId = mutation.conversationId
            newMessagesButton.isVisible = false
            messageList.post { scrollToBottom(smooth = false) }
            return
        }
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
        if (editFocusAnchor != null) {
            messageList.doOnPreDraw { correctEditFocusAfterLayout() }
        }
        if (pendingNotificationPostId != null) {
            messageList.post(::scrollToPendingNotification)
        }
    }

    private fun scrollToPendingNotification() {
        val postId = pendingNotificationPostId ?: return
        val position = adapter.positionOfServerId(postId)
        if (position == RecyclerView.NO_POSITION) return
        layoutManager.scrollToPositionWithOffset(position, messageList.height / 3)
        pendingNotificationPostId = null
    }

    private fun Intent.consumeNotificationPostId(): String? =
        getStringExtra(BackgroundPollingWorker.EXTRA_NOTIFICATION_POST_ID)
            ?.takeIf(String::isNotBlank)
            ?.also { removeExtra(BackgroundPollingWorker.EXTRA_NOTIFICATION_POST_ID) }

    private fun requestBottomRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBottomRefreshAt < 2_000) return
        lastBottomRefreshAt = now
        viewModel.refreshLatest()
    }

    private fun showFullscreenImage(image: FeedImage) {
        showFullscreenImage(image, ImageZoomState())
    }

    private fun showFullscreenImage(image: FeedImage, initialZoom: ImageZoomState) {
        fullscreenImageDialog?.dismiss()
        val dialog = Dialog(this, R.style.Theme_ChatterMore_FullscreenImage).apply {
            setContentView(R.layout.dialog_fullscreen_image)
        }
        val imageView = dialog.findViewById<ZoomableImageView>(R.id.fullscreenImage).apply {
            contentDescription = image.name.ifBlank { getString(R.string.image_attachment) }
            restoreZoomState(initialZoom)
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
        activeFullscreenImage = image
        activeFullscreenImageView = imageView
        val request = imageLoader.loadFullscreen(image) { bitmap ->
            progress.isVisible = false
            if (bitmap == null) {
                error.isVisible = true
            } else {
                imageView.setZoomableBitmap(bitmap)
            }
        }
        dialog.setOnDismissListener {
            request.cancel()
            if (fullscreenImageDialog === dialog) {
                fullscreenImageDialog = null
                activeFullscreenImage = null
                activeFullscreenImageView = null
            }
        }
    }

    private fun restorePendingImage() {
        val restore = pendingImageRestore ?: return
        pendingImageRestore = null
        messageList.post {
            showFullscreenImage(restore.image, restore.zoomState)
        }
    }

    private fun captureFullscreenImage(): FullscreenImageRestore? {
        val image = activeFullscreenImage
        return if (image != null) {
            FullscreenImageRestore(
                image = image,
                zoomState = activeFullscreenImageView?.currentZoomState() ?: ImageZoomState(),
            )
        } else {
            pendingImageRestore
        }
    }

    private fun openYouTubeVideo(video: YouTubeVideo) {
        openExternalUrl(video.url)
    }

    private fun openLinkPreview(preview: LinkPreview) {
        openExternalUrl(preview.url)
    }

    private fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            Toast.makeText(this, R.string.unable_to_open_link, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
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

    @OptIn(markerClass = [UnstableApi::class])
    private fun showVideo(
        file: FeedFile,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): Boolean {
        val source = imageLoader.mediaSource(file.fileId) ?: return false
        fullscreenVideoDialog?.dismiss()
        val dialog = Dialog(this, R.style.Theme_ChatterMore_FullscreenImage).apply {
            setContentView(R.layout.dialog_video)
        }
        val playerView = dialog.findViewById<PlayerView>(R.id.fullscreenVideo)
        val progress = dialog.findViewById<ProgressBar>(R.id.fullscreenVideoProgress)
        val errorPanel = dialog.findViewById<View>(R.id.fullscreenVideoErrorPanel)
        val errorMessage = dialog.findViewById<TextView>(R.id.fullscreenVideoError)
        val errorDetails = dialog.findViewById<TextView>(R.id.fullscreenVideoErrorDetails)
        val retry = dialog.findViewById<Button>(R.id.retryFullscreenVideo)
        dialog.findViewById<TextView>(R.id.fullscreenVideoTitle).text = file.name
        dialog.findViewById<ImageButton>(R.id.closeFullscreenVideo).setOnClickListener {
            dialog.dismiss()
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(VIDEO_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(VIDEO_READ_TIMEOUT_MS)
            .setDefaultRequestProperties(source.headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        playerView.player = player

        fun preparePlayback(positionMs: Long, autoPlay: Boolean) {
            errorPanel.isVisible = false
            progress.isVisible = true
            player.setMediaItem(MediaItem.fromUri(source.uri), positionMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = autoPlay
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> progress.isVisible = true
                    Player.STATE_READY, Player.STATE_ENDED -> progress.isVisible = false
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                progress.isVisible = false
                val failure = VideoPlaybackErrorDetails.from(error)
                errorMessage.setText(
                    when (failure.kind) {
                        VideoPlaybackErrorKind.NETWORK -> R.string.video_error_network
                        VideoPlaybackErrorKind.MALFORMED -> R.string.video_error_malformed
                        VideoPlaybackErrorKind.UNSUPPORTED -> R.string.video_error_unsupported
                        VideoPlaybackErrorKind.UNKNOWN -> R.string.video_load_failed
                    },
                )
                errorDetails.text = failure.technicalDetails
                errorPanel.isVisible = true
            }
        })
        retry.setOnClickListener { preparePlayback(player.currentPosition, true) }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        fullscreenVideoDialog = dialog
        activeVideoFile = file
        activeVideoPlayer = player
        dialog.setOnDismissListener {
            playerView.player = null
            player.release()
            if (fullscreenVideoDialog === dialog) {
                fullscreenVideoDialog = null
                activeVideoFile = null
                activeVideoPlayer = null
            }
        }
        preparePlayback(startPositionMs, playWhenReady)
        playerView.requestFocus()
        return true
    }

    private fun restorePendingVideo() {
        val restore = pendingVideoRestore ?: return
        pendingVideoRestore = null
        messageList.post {
            if (!showVideo(restore.file, restore.safePositionMs, restore.playWhenReady)) {
                pendingVideoRestore = restore
            }
        }
    }

    private fun captureVideoPlayback(): VideoPlaybackRestore? {
        val player = activeVideoPlayer
        val file = activeVideoFile
        return if (player != null && file != null) {
            VideoPlaybackRestore(file, player.currentPosition, player.playWhenReady)
        } else {
            pendingVideoRestore
        }
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

    private fun captureScrollAnchor(): MessageScrollAnchor? {
        if (!::adapter.isInitialized || adapter.itemCount == 0) return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val item = adapter.itemAt(position) ?: return null
        val topOffset = layoutManager.findViewByPosition(position)?.top ?: return null
        return MessageScrollAnchor(
            itemKey = item.key,
            fallbackPosition = position,
            topOffset = topOffset,
        )
    }

    private fun captureItemAnchor(itemKey: String): EditFocusAnchor? {
        val position = adapter.positionOf(itemKey)
        if (position == RecyclerView.NO_POSITION) return null
        val itemView = layoutManager.findViewByPosition(position) ?: return null
        return EditFocusAnchor(
            scrollAnchor = MessageScrollAnchor(
                itemKey = itemKey,
                fallbackPosition = position,
                topOffset = itemView.top,
            ),
            itemHeight = itemView.height,
            targetTop = itemView.top,
        )
    }

    private fun captureServerPostAnchor(serverId: String): EditFocusAnchor? {
        val position = adapter.positionOfServerId(serverId)
        val item = adapter.itemAt(position) ?: return null
        return captureItemAnchor(item.key)
    }

    private fun scheduleEditFocusRelease() {
        if (editFocusAnchor == null) return
        messageList.removeCallbacks(releaseEditFocusAnchor)
        messageList.postDelayed(releaseEditFocusAnchor, EDIT_FOCUS_HOLD_MS)
    }

    private fun correctEditFocusAfterLayout() {
        val anchor = editFocusAnchor ?: return
        val scrollAnchor = anchor.scrollAnchor
        val keyedPosition = adapter.positionOf(scrollAnchor.itemKey)
            .takeUnless { it == RecyclerView.NO_POSITION }
        val position = scrollAnchor.resolvePosition(adapter.itemCount, keyedPosition) ?: return
        val calculatedTargetTop = editFocusTopOffset(
            originalTop = scrollAnchor.topOffset,
            itemHeight = anchor.itemHeight,
            viewportHeight = messageList.height,
            paddingTop = messageList.paddingTop,
            paddingBottom = messageList.paddingBottom,
        )
        val targetTop = nextEditFocusTop(anchor.targetTop, calculatedTargetTop)
        anchor.targetTop = targetTop
        val itemView = layoutManager.findViewByPosition(position)
        if (itemView == null) {
            layoutManager.scrollToPositionWithOffset(position, targetTop)
            return
        }
        val scrollDelta = itemView.top - targetTop
        if (scrollDelta != 0) messageList.scrollBy(0, scrollDelta)
    }

    private fun restoreScrollAnchor(anchor: MessageScrollAnchor?) {
        if (anchor == null) return
        val keyedPosition = adapter.positionOf(anchor.itemKey)
            .takeUnless { it == RecyclerView.NO_POSITION }
        anchor.resolvePosition(adapter.itemCount, keyedPosition)?.let { position ->
            layoutManager.scrollToPositionWithOffset(position, anchor.topOffset)
        }
        if (pendingScrollAnchor == anchor) pendingScrollAnchor = null
    }

    private fun Bundle.scrollAnchor(): MessageScrollAnchor? {
        val itemKey = getString(STATE_SCROLL_ITEM_KEY) ?: return null
        return MessageScrollAnchor(
            itemKey = itemKey,
            fallbackPosition = getInt(STATE_SCROLL_POSITION),
            topOffset = getInt(STATE_SCROLL_OFFSET),
        )
    }

    private fun Bundle.videoPlaybackRestore(): VideoPlaybackRestore? {
        val fileId = getString(STATE_VIDEO_FILE_ID)?.takeIf(String::isNotBlank) ?: return null
        return VideoPlaybackRestore(
            file = FeedFile(
                fileId = fileId,
                name = getString(STATE_VIDEO_FILE_NAME).orEmpty().ifBlank { fileId },
                mimeType = getString(STATE_VIDEO_MIME_TYPE).orEmpty()
                    .ifBlank { "application/octet-stream" },
                sizeBytes = getLong(STATE_VIDEO_SIZE_BYTES, -1L),
            ),
            positionMs = getLong(STATE_VIDEO_POSITION_MS, 0L),
            playWhenReady = getBoolean(STATE_VIDEO_PLAY_WHEN_READY, true),
        )
    }

    private fun Bundle.fullscreenImageRestore(): FullscreenImageRestore? {
        val fileId = getString(STATE_IMAGE_FILE_ID)?.takeIf(String::isNotBlank) ?: return null
        return FullscreenImageRestore(
            image = FeedImage(
                fileId = fileId,
                name = getString(STATE_IMAGE_FILE_NAME).orEmpty().ifBlank { fileId },
                width = getInt(STATE_IMAGE_WIDTH),
                height = getInt(STATE_IMAGE_HEIGHT),
                hasPreviewImage = getBoolean(STATE_IMAGE_HAS_PREVIEW),
            ),
            zoomState = ImageZoomState(
                zoom = getFloat(STATE_IMAGE_ZOOM, 1f),
                centerXFraction = getFloat(STATE_IMAGE_CENTER_X, 0.5f),
                centerYFraction = getFloat(STATE_IMAGE_CENTER_Y, 0.5f),
            ),
        )
    }

    private fun FullscreenImageRestore.writeTo(bundle: Bundle) {
        bundle.putString(STATE_IMAGE_FILE_ID, image.fileId)
        bundle.putString(STATE_IMAGE_FILE_NAME, image.name)
        bundle.putInt(STATE_IMAGE_WIDTH, image.width)
        bundle.putInt(STATE_IMAGE_HEIGHT, image.height)
        bundle.putBoolean(STATE_IMAGE_HAS_PREVIEW, image.hasPreviewImage)
        bundle.putFloat(STATE_IMAGE_ZOOM, zoomState.zoom)
        bundle.putFloat(STATE_IMAGE_CENTER_X, zoomState.centerXFraction)
        bundle.putFloat(STATE_IMAGE_CENTER_Y, zoomState.centerYFraction)
    }

    private fun VideoPlaybackRestore.writeTo(bundle: Bundle) {
        bundle.putString(STATE_VIDEO_FILE_ID, file.fileId)
        bundle.putString(STATE_VIDEO_FILE_NAME, file.name)
        bundle.putString(STATE_VIDEO_MIME_TYPE, file.mimeType)
        bundle.putLong(STATE_VIDEO_SIZE_BYTES, file.sizeBytes)
        bundle.putLong(STATE_VIDEO_POSITION_MS, safePositionMs)
        bundle.putBoolean(STATE_VIDEO_PLAY_WHEN_READY, playWhenReady)
    }

    private fun scrollToBottom(smooth: Boolean) {
        val last = adapter.itemCount - 1
        if (last < 0) return
        if (smooth) messageList.smoothScrollToPosition(last) else messageList.scrollToPosition(last)
    }

    private fun populateSetup(config: ServerConfig) {
        if (serverUrl.text.isBlank()) serverUrl.setText(config.baseUrl)
        if (username.text.isBlank()) username.setText(config.username)
        if (config.password.isBlank()) {
            password.text.clear()
        } else if (password.text.isBlank()) {
            password.setText(config.password)
        }
    }

    private fun setSetupEnabled(enabled: Boolean) {
        serverUrl.isEnabled = enabled
        username.isEnabled = enabled
        password.isEnabled = enabled
        connectButton.isEnabled = enabled
    }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { manager.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun focusMessageInputForEdit() {
        messageInput.post {
            requestMessageInputFocus()
            messageInput.postDelayed(::requestMessageInputFocus, EDIT_FOCUS_REASSERT_MS)
        }
    }

    private fun focusMessageInputForShare() {
        messageInput.post {
            if (viewModel.messageEdit.value != null) return@post
            messageInput.isFocusableInTouchMode = true
            messageInput.requestFocus()
            messageInput.setSelection(messageInput.text.length)
            WindowCompat.getInsetsController(window, messageInput)
                .show(WindowInsetsCompat.Type.ime())
            val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun requestMessageInputFocus() {
        if (viewModel.messageEdit.value == null) return
        messageInput.isFocusableInTouchMode = true
        messageInput.requestFocus()
        messageInput.setSelection(messageInput.text.length)
        WindowCompat.getInsetsController(window, messageInput)
            .show(WindowInsetsCompat.Type.ime())
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private companion object {
        const val VIDEO_CONNECT_TIMEOUT_MS = 10_000
        const val VIDEO_READ_TIMEOUT_MS = 20_000
        const val EDIT_FOCUS_HOLD_MS = 800L
        const val EDIT_FOCUS_REASSERT_MS = 140L
        const val EXTRA_BENCHMARK_MESSAGE_COUNT = "benchmark_message_count"
        const val EXTRA_BENCHMARK_SCREEN = "benchmark_screen"
        const val STATE_SCROLL_ITEM_KEY = "scroll_item_key"
        const val STATE_SCROLL_POSITION = "scroll_position"
        const val STATE_SCROLL_OFFSET = "scroll_offset"
        const val STATE_IMAGE_FILE_ID = "image_file_id"
        const val STATE_IMAGE_FILE_NAME = "image_file_name"
        const val STATE_IMAGE_WIDTH = "image_width"
        const val STATE_IMAGE_HEIGHT = "image_height"
        const val STATE_IMAGE_HAS_PREVIEW = "image_has_preview"
        const val STATE_IMAGE_ZOOM = "image_zoom"
        const val STATE_IMAGE_CENTER_X = "image_center_x"
        const val STATE_IMAGE_CENTER_Y = "image_center_y"
        const val STATE_VIDEO_FILE_ID = "video_file_id"
        const val STATE_VIDEO_FILE_NAME = "video_file_name"
        const val STATE_VIDEO_MIME_TYPE = "video_mime_type"
        const val STATE_VIDEO_SIZE_BYTES = "video_size_bytes"
        const val STATE_VIDEO_POSITION_MS = "video_position_ms"
        const val STATE_VIDEO_PLAY_WHEN_READY = "video_play_when_ready"
        const val STATE_CAPTURE_URI = "capture_uri"
        const val MAX_ATTACHMENTS = 10
        const val ATTACHMENT_ANIMATION_MS = 180L
        const val MENTION_ROW_HEIGHT_DP = 56
        const val MAX_VISIBLE_MENTION_SUGGESTIONS = 5
    }
}
