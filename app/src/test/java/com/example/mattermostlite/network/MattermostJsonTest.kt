package com.example.mattermostlite.network

import com.example.mattermostlite.model.SocketSignal
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MattermostJsonTest {
    @Test
    fun `parses Mattermost hello event used for the resume cursor`() {
        val signal = MattermostSocket(OkHttpClient()).parseSignal(
            """
            {
              "event":"hello",
              "seq":4,
              "data":{"server_version":"11.7.2","connection_id":"connection-1"}
            }
            """.trimIndent(),
        )

        assertTrue(signal is SocketSignal.Hello)
        signal as SocketSignal.Hello
        assertEquals("connection-1", signal.connectionId)
        assertEquals(4L, signal.sequence)
    }

    @Test
    fun `parses team needed for separate channel resolution`() {
        val team = parseTeam(
            """{"id":"team-id","name":"engineering","display_name":"Engineering Team"}""",
        )

        assertEquals("team-id", team.id)
        assertEquals("engineering", team.name)
        assertEquals("Engineering Team", team.displayName)
    }

    @Test
    fun `parses display and url names from team and channel lists`() {
        val teams = parseTeams(
            """[{"id":"team-id","name":"card-dwire","display_name":"Card D'Wire"}]""",
        )
        val channels = parseChannels(
            """[{"id":"channel-id","name":"release-chat","display_name":"Release Chat"}]""",
        )

        assertEquals("card-dwire", teams.single().name)
        assertEquals("Card D'Wire", teams.single().displayName)
        assertEquals("release-chat", channels.single().name)
        assertEquals("Release Chat", channels.single().displayName)
    }

    @Test
    fun `post list follows server order`() {
        val page = parsePostPage(
            """
            {
              "order": ["new", "old"],
              "posts": {
                "old": {"id":"old","channel_id":"c","user_id":"u","message":"old","create_at":1},
                "new": {"id":"new","channel_id":"c","user_id":"u","message":"new","create_at":2}
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("new", "old"), page.orderedPosts.map { it.id })
    }

    @Test
    fun `parses attached image metadata from a post`() {
        val post = parsePost(
            """
            {
              "id":"post","channel_id":"c","user_id":"u","message":"","create_at":1,
              "file_ids":["image-id","document-id"],
              "metadata":{"files":[
                {"id":"image-id","name":"photo.jpg","mime_type":"image/jpeg","width":1600,"height":900,"has_preview_image":true},
                {"id":"document-id","name":"notes.pdf","mime_type":"application/pdf","width":0,"height":0,"size":12400000}
              ]}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("image-id", "document-id"), post.fileIds)
        assertEquals(2, post.files.size)
        assertEquals("image/jpeg", post.files.first().mimeType)
        assertEquals(1600, post.files.first().width)
        assertEquals(900, post.files.first().height)
        assertTrue(post.files.first().hasPreviewImage)
        assertEquals(12_400_000L, post.files.last().sizeBytes)
    }

    @Test
    fun `parses posted websocket envelope`() {
        val socket = MattermostSocket(OkHttpClient())
        val signal = socket.parseSignal(
            """
            {
              "event":"posted",
              "seq":7,
              "broadcast":{"channel_id":"c"},
              "data":{"post":"{\"id\":\"p\",\"channel_id\":\"c\",\"user_id\":\"u\",\"message\":\"hello\",\"create_at\":10}"}
            }
            """.trimIndent(),
        )

        assertTrue(signal is SocketSignal.PostEvent)
        signal as SocketSignal.PostEvent
        assertEquals("p", signal.post.id)
        assertEquals("c", signal.channelId)
        assertEquals(7L, signal.sequence)
    }

    @Test
    fun `creates post body with stable pending id`() {
        val json = mattermostJson.parseToJsonElement(createPostBody("c", "hello", "pending")).jsonObject
        assertEquals("c", json.string("channel_id"))
        assertEquals("hello", json.string("message"))
        assertEquals("pending", json.string("pending_post_id"))
    }

    @Test
    fun `creates attachment-only post body with uploaded file ids`() {
        val json = mattermostJson.parseToJsonElement(
            createPostBody("c", "", "pending", listOf("file-1", "file-2")),
        ).jsonObject

        assertEquals("", json.string("message"))
        assertEquals(listOf("file-1", "file-2"), json.stringList("file_ids"))
    }

    @Test
    fun `parses upload response and client upload limits`() {
        val files = parseUploadedFiles(
            """{"file_infos":[{"id":"file-1","name":"photo.jpg","mime_type":"image/jpeg","size":42}]}""",
        )
        val capabilities = parseUploadCapabilities(
            """{"EnableFileAttachments":"true","EnableMobileFileUpload":"true","MaxFileSize":"104857600"}""",
        )

        assertEquals("file-1", files.single().id)
        assertEquals(42L, files.single().sizeBytes)
        assertTrue(capabilities.enabled)
        assertEquals(104_857_600L, capabilities.maxFileSizeBytes)
    }

    @Test
    fun `disabled server attachment setting disables uploads`() {
        val capabilities = parseUploadCapabilities(
            """{"EnableFileAttachments":"false","EnableMobileFileUpload":"true"}""",
        )

        assertFalse(capabilities.enabled)
        assertNull(capabilities.maxFileSizeBytes)
    }

    @Test
    fun `parses edit timestamp and creates a message patch body`() {
        val post = parsePost(
            """{"id":"post","channel_id":"c","user_id":"u","message":"updated","create_at":1,"update_at":2,"edit_at":2}""",
        )
        val patch = mattermostJson.parseToJsonElement(patchPostBody("updated")).jsonObject

        assertEquals(2L, post.editAt)
        assertEquals("updated", patch.string("message"))
        assertEquals(setOf("message"), patch.keys)
    }

    @Test
    fun `creates username password login body`() {
        val json = mattermostJson.parseToJsonElement(loginBody("marco", "quote\"slash\\" )).jsonObject

        assertEquals("marco", json.string("login_id"))
        assertEquals("quote\"slash\\", json.string("password"))
    }
}
