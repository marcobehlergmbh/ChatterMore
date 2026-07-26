package com.example.mattermostlite.background

import com.example.mattermostlite.model.MattermostPost

object BackgroundPollPlanner {
    fun newest(posts: Collection<MattermostPost>): MattermostPost? =
        posts.maxWithOrNull(compareBy(MattermostPost::createAt, MattermostPost::id))

    fun notificationCandidates(
        posts: Collection<MattermostPost>,
        currentUserId: String,
        currentUsername: String,
        mode: BackgroundNotificationMode,
    ): List<MattermostPost> = posts.asSequence()
        .filter { post ->
            post.id.isNotBlank() &&
                post.userId.isNotBlank() &&
                post.userId != currentUserId &&
                post.rootId.isBlank() &&
                post.deleteAt == 0L &&
                post.editAt == 0L &&
                post.type.isBlank()
        }
        .filter { mode == BackgroundNotificationMode.ALL || mentions(post = it, currentUsername) }
        .distinctBy(MattermostPost::id)
        .sortedWith(compareBy(MattermostPost::createAt, MattermostPost::id))
        .toList()

    fun mentions(post: MattermostPost, username: String): Boolean {
        if ('@' !in post.message) return false
        val targets = buildList {
            username.trim().removePrefix("@").takeIf(String::isNotBlank)?.let(::add)
            add("channel")
            add("all")
            add("here")
        }
        return targets.any { target ->
            Regex(
                pattern = "(?i)(^|[^A-Za-z0-9._-])@${Regex.escape(target)}(?=$|[^A-Za-z0-9._-])",
            ).containsMatchIn(post.message)
        }
    }

    fun messageText(post: MattermostPost, showPreview: Boolean): String = when {
        !showPreview -> "New message"
        post.message.isNotBlank() -> post.message
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_PREVIEW_CHARACTERS)
        post.fileIds.isNotEmpty() || post.files.isNotEmpty() -> "Sent an attachment"
        else -> "New message"
    }

    private const val MAX_PREVIEW_CHARACTERS = 240
}
