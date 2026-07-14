package com.example.mattermostlite.network

import com.example.mattermostlite.model.SocketSignal
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MattermostJsonTest {
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
                {"id":"document-id","name":"notes.pdf","mime_type":"application/pdf","width":0,"height":0}
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
}
