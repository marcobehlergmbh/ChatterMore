package com.marcobehler.chattermore

import com.marcobehler.chattermore.background.BackgroundNotificationMode
import com.marcobehler.chattermore.background.BackgroundPollingSettings
import com.marcobehler.chattermore.model.ConversationKind
import com.marcobehler.chattermore.model.ConversationSummary
import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedFile
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.LinkPreview
import com.marcobehler.chattermore.model.MattermostChannel
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.MentionContext
import com.marcobehler.chattermore.model.UserPresence

data class BenchmarkFixtureState(
    val screen: ScreenState,
    val items: List<FeedItem>,
    val users: List<MattermostUser>,
    val presence: Map<String, UserPresence>,
    val mentionContext: MentionContext,
    val backgroundSettings: BackgroundPollingSettings,
)

object BenchmarkFixtures {
    const val MODE_OVERVIEW = "store-overview"
    const val MODE_CHANNEL = "store-channel"
    const val MODE_DIRECT_MESSAGE = "store-direct-message"
    const val MODE_ATTACHMENTS = "store-attachments"
    const val MODE_SETTINGS = "store-settings"
    const val MODE_QUOTES = "quote-preview"

    private const val CURRENT_USER_ID = "fixture-marco"
    private const val CURRENT_USERNAME = "marco"

    private val users = listOf(
        MattermostUser(CURRENT_USER_ID, CURRENT_USERNAME, "Marco", ""),
        MattermostUser("fixture-maya", "maya", "Maya", "Chen"),
        MattermostUser("fixture-elena", "elena", "Elena", "Ortiz"),
        MattermostUser("fixture-daniel", "daniel", "Daniel", "Brooks"),
        MattermostUser("fixture-priya", "priya", "Priya", "Shah"),
    )

    private val presence = mapOf(
        CURRENT_USER_ID to UserPresence.ACTIVE,
        "fixture-maya" to UserPresence.ACTIVE,
        "fixture-elena" to UserPresence.ACTIVE,
        "fixture-daniel" to UserPresence.AWAY,
        "fixture-priya" to UserPresence.OFFLINE,
    )

    private val mentionContext = MentionContext(
        currentUserId = CURRENT_USER_ID,
        currentUsername = CURRENT_USERNAME,
        usersByUsername = users.associateBy(MattermostUser::username),
    )

    fun create(
        mode: String?,
        performanceMessageCount: Int,
        now: Long = System.currentTimeMillis(),
    ): BenchmarkFixtureState {
        val base = BenchmarkFixtureState(
            screen = ScreenState.Conversation(
                id = "benchmark",
                title = "Performance fixture",
            ),
            items = performanceItems(performanceMessageCount),
            users = users,
            presence = presence,
            mentionContext = mentionContext,
            backgroundSettings = BackgroundPollingSettings(),
        )
        return when (mode) {
            MODE_OVERVIEW -> base.copy(
                screen = ScreenState.Overview(
                    teamId = "fixture-team",
                    teamTitle = "Northstar Studio",
                    conversations = overviewConversations(now),
                ),
                items = emptyList(),
            )
            MODE_CHANNEL -> base.copy(
                screen = conversationScreen("fixture-product-launch", "Product Launch"),
                items = channelItems(now),
            )
            MODE_DIRECT_MESSAGE -> base.copy(
                screen = conversationScreen(
                    id = "fixture-dm-maya",
                    title = "Maya Chen",
                    peerUserId = "fixture-maya",
                ),
                items = directMessageItems(now),
            )
            MODE_ATTACHMENTS -> base.copy(
                screen = conversationScreen("fixture-launch-assets", "Launch Assets"),
                items = attachmentItems(now),
            )
            MODE_SETTINGS -> base.copy(
                screen = conversationScreen("fixture-product-launch", "Product Launch"),
                items = channelItems(now).takeLast(4),
                backgroundSettings = BackgroundPollingSettings(
                    enabled = true,
                    mode = BackgroundNotificationMode.MENTIONS,
                    showPreview = true,
                ),
            )
            MODE_QUOTES -> base.copy(
                screen = conversationScreen("fixture-quote-preview", "Quote Preview"),
                items = quoteItems(now),
            )
            else -> base
        }
    }

    private fun conversationScreen(
        id: String,
        title: String,
        peerUserId: String? = null,
    ) = ScreenState.Conversation(
        id = id,
        title = title,
        canNavigateBack = true,
        peerUserId = peerUserId,
    )

    private fun overviewConversations(now: Long): List<ConversationSummary> = listOf(
        conversationSummary("fixture-town-square", "Town Square", ConversationKind.CHANNEL, now, 4, true),
        conversationSummary("fixture-product-launch", "Product Launch", ConversationKind.CHANNEL, now, 12, true, 2),
        conversationSummary("fixture-design", "Design", ConversationKind.CHANNEL, now, 47),
        conversationSummary("fixture-random", "Random", ConversationKind.CHANNEL, now, 95),
        conversationSummary("fixture-dm-maya", "Maya Chen", ConversationKind.DIRECT_MESSAGE, now, 7, true, peer = "fixture-maya"),
        conversationSummary("fixture-dm-daniel", "Daniel Brooks", ConversationKind.DIRECT_MESSAGE, now, 38, peer = "fixture-daniel"),
        conversationSummary("fixture-dm-elena", "Elena Ortiz", ConversationKind.DIRECT_MESSAGE, now, 180, peer = "fixture-elena"),
    )

    private fun conversationSummary(
        id: String,
        title: String,
        kind: ConversationKind,
        now: Long,
        minutesAgo: Int,
        unread: Boolean = false,
        mentionCount: Long = 0,
        peer: String? = null,
    ): ConversationSummary {
        val lastPostAt = now - minutesAgo * 60_000L
        return ConversationSummary(
            channel = MattermostChannel(
                id = id,
                name = id.removePrefix("fixture-"),
                displayName = title,
                teamId = "fixture-team",
                type = if (kind == ConversationKind.CHANNEL) "O" else "D",
                lastPostAt = lastPostAt,
            ),
            kind = kind,
            title = title,
            peerUserId = peer,
            peerUsername = users.firstOrNull { it.id == peer }?.username,
            lastPostAt = lastPostAt,
            unread = unread,
            mentionCount = mentionCount,
        )
    }

    private fun channelItems(now: Long): List<FeedItem> = listOf(
        item(1, "fixture-product-launch", "fixture-elena", "Elena Ortiz", "Morning! The final launch checklist is ready.", now, 34),
        item(2, "fixture-product-launch", "fixture-daniel", "Daniel Brooks", "The Android build passed on both test devices.", now, 28),
        item(3, "fixture-product-launch", "fixture-maya", "Maya Chen", "@marco Could you review the release notes before 16:00?", now, 21),
        item(4, "fixture-product-launch", CURRENT_USER_ID, "You", "On it — I’ll post the final copy here.", now, 15),
        item(5, "fixture-product-launch", "fixture-elena", "Elena Ortiz", "Great. I moved the screenshots into the launch folder.", now, 9),
        item(6, "fixture-product-launch", "fixture-maya", "Maya Chen", "Looks good from my side 🎉", now, 4),
    )

    private fun directMessageItems(now: Long): List<FeedItem> = listOf(
        item(1, "fixture-dm-maya", "fixture-maya", "Maya Chen", "Do you have a minute to check the rollout notes?", now, 31),
        item(2, "fixture-dm-maya", CURRENT_USER_ID, "You", "Yes — reading them now.", now, 27),
        item(3, "fixture-dm-maya", "fixture-maya", "Maya Chen", "The final section has the device test results.", now, 19),
        item(4, "fixture-dm-maya", CURRENT_USER_ID, "You", "Found it. Everything looks ready for the closed test.", now, 11, edited = true),
        item(5, "fixture-dm-maya", "fixture-maya", "Maya Chen", "Perfect, thank you!", now, 5),
    )

    private fun attachmentItems(now: Long): List<FeedItem> = listOf(
        item(
            index = 1,
            channelId = "fixture-launch-assets",
            userId = "fixture-elena",
            author = "Elena Ortiz",
            message = "Here are the final store assets.",
            now = now,
            minutesAgo = 24,
            files = listOf(
                FeedFile(
                    fileId = "fixture-feature-graphic",
                    name = "chattermore-feature-graphic.png",
                    mimeType = "image/png",
                    sizeBytes = 409_417,
                ),
                FeedFile(
                    fileId = "fixture-release-notes",
                    name = "release-notes.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1_843_200,
                ),
            ),
        ),
        item(
            index = 2,
            channelId = "fixture-launch-assets",
            userId = "fixture-daniel",
            author = "Daniel Brooks",
            message = "The walkthrough video is ready too.",
            now = now,
            minutesAgo = 14,
            files = listOf(
                FeedFile(
                    fileId = "fixture-walkthrough",
                    name = "android-walkthrough.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 8_912_896,
                ),
            ),
        ),
        item(
            index = 3,
            channelId = "fixture-launch-assets",
            userId = CURRENT_USER_ID,
            author = "You",
            message = "I added the final rollout plan: https://example.com/rollout",
            now = now,
            minutesAgo = 5,
            linkPreviews = listOf(
                LinkPreview(
                    url = "https://example.com/rollout",
                    title = "ChatterMore rollout plan",
                    description = "Closed testing, device coverage, and release milestones.",
                    siteName = "Example",
                ),
            ),
        ),
    )

    private fun quoteItems(now: Long): List<FeedItem> = listOf(
        item(
            1,
            "fixture-quote-preview",
            "fixture-maya",
            "Maya Chen",
            "I pulled the key points from the rollout plan:",
            now,
            12,
        ),
        item(
            2,
            "fixture-quote-preview",
            "fixture-daniel",
            "Daniel Brooks",
            "> Keep the first release focused.\n" +
                "> Verify notifications on a real device.\n" +
                "> Document the reviewer login clearly.",
            now,
            9,
        ),
        item(
            3,
            "fixture-quote-preview",
            CURRENT_USER_ID,
            "You",
            "That’s a good summary. @maya I’ll use it for the closed test.",
            now,
            6,
        ),
        item(
            4,
            "fixture-quote-preview",
            "fixture-elena",
            "Elena Ortiz",
            "Here is the exact decision:\n\n" +
                "> Ship the focused feature set first.\n\n" +
                "We can expand after feedback.",
            now,
            3,
        ),
    )

    private fun item(
        index: Int,
        channelId: String,
        userId: String,
        author: String,
        message: String,
        now: Long,
        minutesAgo: Int,
        files: List<FeedFile> = emptyList(),
        linkPreviews: List<LinkPreview> = emptyList(),
        edited: Boolean = false,
    ): FeedItem {
        val timestamp = now - minutesAgo * 60_000L
        return FeedItem(
            key = "fixture:$channelId:$index",
            serverId = "fixture-post-$channelId-$index",
            pendingId = null,
            channelId = channelId,
            userId = userId,
            author = author,
            message = message,
            createAt = timestamp,
            updateAt = timestamp,
            deliveryState = DeliveryState.SENT,
            files = files,
            linkPreviews = linkPreviews,
            editAt = timestamp.takeIf { edited } ?: 0,
        )
    }

    private fun performanceItems(count: Int): List<FeedItem> =
        (1..count.coerceIn(1, 2_000)).map { index ->
            FeedItem(
                key = "benchmark:$index",
                serverId = "benchmark-$index",
                pendingId = null,
                channelId = "benchmark",
                userId = "user-${index % 12}",
                author = "User ${index % 12}",
                message = if (index % 11 == 0) {
                    "A benchmark link preview at row $index https://example.com/releases/$index"
                } else if (index % 17 == 0) {
                    "@channel benchmark broadcast mention at row $index"
                } else if (index % 7 == 0) {
                    "A longer benchmark message at row $index that wraps across multiple lines to exercise text layout without Markdown or attachments."
                } else if (index % 13 == 0) {
                    "A benchmark message with render-time smileys at row $index :) <3"
                } else {
                    "Benchmark message $index"
                },
                createAt = 1_700_000_000_000L + index * 1_000L,
                updateAt = 1_700_000_000_000L + index * 1_000L,
                deliveryState = DeliveryState.SENT,
                linkPreviews = if (index % 11 == 0) {
                    listOf(
                        LinkPreview(
                            url = "https://example.com/releases/$index",
                            title = "Release notes $index",
                            description = "A compact benchmark preview without a network image.",
                            siteName = "Example",
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        }
}
