package com.example.mattermostlite.background

import com.example.mattermostlite.model.MattermostPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPollPlannerTest {
    @Test
    fun `mentions mode recognizes personal and channel mentions with safe boundaries`() {
        assertTrue(BackgroundPollPlanner.mentions(post("one", "@marco please check"), "marco"))
        assertTrue(BackgroundPollPlanner.mentions(post("two", "Heads up @channel"), "marco"))
        assertTrue(BackgroundPollPlanner.mentions(post("three", "@HERE now"), "marco"))
        assertFalse(BackgroundPollPlanner.mentions(post("four", "hello marco"), "marco"))
        assertFalse(BackgroundPollPlanner.mentions(post("five", "@marco-long is someone else"), "marco"))
    }

    @Test
    fun `candidate planning excludes own replies deletes edits and system posts`() {
        val candidates = BackgroundPollPlanner.notificationCandidates(
            posts = listOf(
                post("new", "@marco newest", createAt = 300),
                post("old", "@marco oldest", createAt = 100),
                post("own", "@marco own", userId = "me"),
                post("reply", "@marco reply", rootId = "root"),
                post("deleted", "@marco deleted", deleteAt = 10),
                post("edited", "@marco edited", editAt = 20),
                post("system", "@marco joined", type = "system_join_channel"),
                post("unmentioned", "ordinary message"),
            ),
            currentUserId = "me",
            currentUsername = "marco",
            mode = BackgroundNotificationMode.MENTIONS,
        )

        assertEquals(listOf("old", "new"), candidates.map(MattermostPost::id))
    }

    @Test
    fun `all mode includes ordinary new top level messages`() {
        val candidates = BackgroundPollPlanner.notificationCandidates(
            posts = listOf(post("ordinary", "No mention")),
            currentUserId = "me",
            currentUsername = "marco",
            mode = BackgroundNotificationMode.ALL,
        )

        assertEquals(listOf("ordinary"), candidates.map(MattermostPost::id))
    }

    @Test
    fun `notification text respects privacy setting and attachment-only posts`() {
        assertEquals("New message", BackgroundPollPlanner.messageText(post("one", "secret"), false))
        assertEquals(
            "Sent an attachment",
            BackgroundPollPlanner.messageText(post("two", "", fileIds = listOf("file")), true),
        )
        assertEquals(
            "line one line two",
            BackgroundPollPlanner.messageText(post("three", " line one\n line two "), true),
        )
        assertEquals(
            240,
            BackgroundPollPlanner.messageText(post("four", "x".repeat(500)), true).length,
        )
    }

    private fun post(
        id: String,
        message: String,
        createAt: Long = 100,
        userId: String = "other",
        rootId: String = "",
        deleteAt: Long = 0,
        editAt: Long = 0,
        type: String = "",
        fileIds: List<String> = emptyList(),
    ) = MattermostPost(
        id = id,
        channelId = "channel",
        userId = userId,
        message = message,
        createAt = createAt,
        updateAt = createAt,
        deleteAt = deleteAt,
        rootId = rootId,
        type = type,
        pendingPostId = "",
        fileIds = fileIds,
        editAt = editAt,
    )
}
