package com.example.mattermostlite.data

import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedMutation
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostFile
import com.example.mattermostlite.model.MattermostUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStoreTest {
    private val user = MattermostUser("user-1", "marco", "Marco", "Behler")

    @Test
    fun `orders posts and exposes seek cursors`() {
        val store = FeedStore("Unsupported")
        store.replace(
            posts = listOf(post("new", 300), post("old", 100), post("middle", 200)),
            profiles = listOf(user),
        )

        assertEquals(listOf("old", "middle", "new"), store.snapshot().map { it.serverId })
        assertEquals("old", store.oldestServerId())
        assertEquals("new", store.newestServerId())
    }

    @Test
    fun `reconciles websocket or rest acknowledgement by pending id`() {
        val store = FeedStore("Unsupported")
        store.addPending("client-1", "channel", user, "hello", 100)
        assertEquals(DeliveryState.OPTIMISTIC, store.snapshot().single().deliveryState)

        val mutations = store.merge(
            listOf(post("server-1", 100, pendingId = "client-1")),
            listOf(user),
        )

        assertEquals(1, store.snapshot().size)
        assertEquals("server-1", store.snapshot().single().serverId)
        assertEquals(DeliveryState.SENT, store.snapshot().single().deliveryState)
        assertTrue(mutations.single() is FeedMutation.Update)
        assertTrue(store.merge(listOf(post("server-1", 100, pendingId = "client-1"))).isEmpty())
    }

    @Test
    fun `filters replies and removes deleted posts`() {
        val store = FeedStore("Unsupported")
        store.replace(
            listOf(post("root", 100), post("reply", 200, rootId = "root")),
            listOf(user),
        )
        assertEquals(listOf("root"), store.snapshot().map { it.serverId })

        val mutations = store.merge(listOf(post("root", 100, deleteAt = 300)))
        assertTrue(mutations.single() is FeedMutation.Remove)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `failed pending post remains retryable`() {
        val store = FeedStore("Unsupported")
        store.addPending("client-1", "channel", user, "hello", 100)
        store.setPendingState("client-1", DeliveryState.FAILED)

        assertEquals(DeliveryState.FAILED, store.pending("client-1")?.deliveryState)
        assertEquals("hello", store.pending("client-1")?.message)
        assertNull(store.pending("missing"))
    }

    @Test
    fun `optimistic post becomes underlined state until acknowledged`() {
        val store = FeedStore("Unsupported")
        store.addPending("client-1", "channel", user, "hello", 100)

        val mutation = store.markUnconfirmed("client-1")

        assertTrue(mutation is FeedMutation.Update)
        assertEquals(DeliveryState.UNCONFIRMED, store.pending("client-1")?.deliveryState)
    }

    @Test
    fun `late timer and failure cannot overwrite confirmed post`() {
        val store = FeedStore("Unsupported")
        store.addPending("client-1", "channel", user, "hello", 100)
        store.merge(listOf(post("server-1", 100, pendingId = "client-1")), listOf(user))

        assertNull(store.markUnconfirmed("client-1"))
        assertNull(store.setPendingState("client-1", DeliveryState.FAILED))
        assertEquals(DeliveryState.SENT, store.snapshot().single().deliveryState)
    }

    @Test
    fun `bounds resident items while preserving newest`() {
        val store = FeedStore("Unsupported", maxItems = 3)
        store.replace((1..5).map { post("post-$it", it.toLong()) }, listOf(user))

        assertEquals(listOf("post-3", "post-4", "post-5"), store.snapshot().map { it.serverId })
        assertFalse(store.snapshot().any { it.serverId == "post-1" })
    }

    @Test
    fun `older pagination slides bounded window toward history`() {
        val store = FeedStore("Unsupported", maxItems = 3)
        store.replace((3..5).map { post("post-$it", it.toLong()) }, listOf(user))

        val mutations = store.mergeOlder((1..2).map { post("post-$it", it.toLong()) })

        assertTrue(mutations.single() is FeedMutation.Replace)
        assertEquals(listOf("post-1", "post-2", "post-3"), store.snapshot().map { it.serverId })
        assertEquals("post-1", store.oldestServerId())
    }

    @Test
    fun `older page is emitted as one prepend mutation`() {
        val store = FeedStore("Unsupported", maxItems = 10)
        store.replace((4..6).map { post("post-$it", it.toLong()) }, listOf(user))

        val mutations = store.mergeOlder((1..3).map { post("post-$it", it.toLong()) })

        val prepend = mutations.single() as FeedMutation.Prepend
        assertEquals(listOf("post-1", "post-2", "post-3"), prepend.items.map { it.serverId })
        assertEquals((1..6).map { "post-$it" }, store.snapshot().map { it.serverId })
    }

    @Test
    fun `pending insert keeps adapter and bounded store in sync`() {
        val store = FeedStore("Unsupported", maxItems = 2)
        store.replace(listOf(post("post-1", 1), post("post-2", 2)), listOf(user))

        val mutation = store.addPending("client-1", "channel", user, "hello", 3)

        assertTrue(mutation is FeedMutation.Replace)
        assertEquals(listOf("post-2", null), store.snapshot().map { it.serverId })
        assertEquals("client-1", store.snapshot().last().pendingId)
    }

    @Test
    fun `image-only post renders its images without unsupported text`() {
        val store = FeedStore("Unsupported")
        val imagePost = post("image", 1).copy(
            message = "",
            fileIds = listOf("photo"),
            files = listOf(
                MattermostFile(
                    id = "photo",
                    name = "photo.jpg",
                    mimeType = "image/jpeg",
                    width = 1200,
                    height = 800,
                    hasPreviewImage = true,
                ),
            ),
        )

        store.replace(listOf(imagePost), listOf(user))

        val item = store.snapshot().single()
        assertEquals("", item.message)
        assertEquals(listOf("photo"), item.images.map { it.fileId })
        assertEquals(1200, item.images.single().width)
    }

    @Test
    fun `non-image attachment exposes its filename for download`() {
        val store = FeedStore("Unsupported")
        val filePost = post("file", 1).copy(
            message = "",
            files = listOf(
                MattermostFile("document", "notes.pdf", "application/pdf", 0, 0, false),
            ),
        )

        store.replace(listOf(filePost), listOf(user))

        val item = store.snapshot().single()
        assertEquals("", item.message)
        assertTrue(item.images.isEmpty())
        assertEquals("notes.pdf", item.files.single().name)
        assertFalse(item.files.single().isVideo)
    }

    @Test
    fun `video attachment is identified for direct playback`() {
        val store = FeedStore("Unsupported")
        val videoPost = post("video", 1).copy(
            message = "",
            files = listOf(
                MattermostFile("video", "demo.mp4", "application/octet-stream", 1920, 1080, true),
            ),
        )

        store.replace(listOf(videoPost), listOf(user))

        val file = store.snapshot().single().files.single()
        assertEquals("demo.mp4", file.name)
        assertTrue(file.isVideo)
    }

    @Test
    fun `metadata-light socket edit preserves an existing image`() {
        val store = FeedStore("Unsupported")
        val image = MattermostFile("photo", "photo.jpg", "image/jpeg", 1200, 800, true)
        store.replace(
            listOf(post("image", 1).copy(fileIds = listOf("photo"), files = listOf(image))),
            listOf(user),
        )

        store.merge(
            listOf(
                post("image", 1).copy(
                    message = "edited",
                    updateAt = 2,
                    fileIds = listOf("photo"),
                ),
            ),
        )

        val item = store.snapshot().single()
        assertEquals("edited", item.message)
        assertEquals(listOf("photo"), item.images.map { it.fileId })
    }

    @Test
    fun `metadata-light socket edit preserves an existing downloadable file`() {
        val store = FeedStore("Unsupported")
        val document = MattermostFile("document", "notes.pdf", "application/pdf", 0, 0, false)
        store.replace(
            listOf(post("file", 1).copy(fileIds = listOf("document"), files = listOf(document))),
            listOf(user),
        )

        store.merge(
            listOf(
                post("file", 1).copy(
                    message = "edited",
                    updateAt = 2,
                    fileIds = listOf("document"),
                ),
            ),
        )

        assertEquals("notes.pdf", store.snapshot().single().files.single().name)
    }

    private fun post(
        id: String,
        createAt: Long,
        pendingId: String = "",
        rootId: String = "",
        deleteAt: Long = 0,
    ) = MattermostPost(
        id = id,
        channelId = "channel",
        userId = user.id,
        message = "message-$id",
        createAt = createAt,
        updateAt = createAt,
        deleteAt = deleteAt,
        rootId = rootId,
        type = "",
        pendingPostId = pendingId,
    )
}
