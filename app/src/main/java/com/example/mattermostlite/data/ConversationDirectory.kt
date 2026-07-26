package com.example.mattermostlite.data

import com.example.mattermostlite.model.ConversationKind
import com.example.mattermostlite.model.ConversationSummary
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostChannelMember
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostUser
import java.util.Locale

internal fun buildConversationDirectory(
    channels: Collection<MattermostChannel>,
    memberships: Collection<MattermostChannelMember>,
    currentUserId: String,
    users: Collection<MattermostUser>,
): List<ConversationSummary> {
    val membershipByChannelId = memberships.associateBy(MattermostChannelMember::channelId)
    val userById = users.associateBy(MattermostUser::id)
    val summaries = channels.asSequence()
        .filter { it.id.isNotBlank() && it.deleteAt == 0L }
        .mapNotNull { channel ->
            val membership = membershipByChannelId[channel.id]
            val unread = membership != null &&
                (channel.totalMessageCount > membership.messageCount || membership.mentionCount > 0)
            when (channel.type.uppercase(Locale.ROOT)) {
                "D" -> {
                    val peerId = directMessagePeerId(channel.name, currentUserId)
                        ?: return@mapNotNull null
                    val peer = userById[peerId]
                    ConversationSummary(
                        channel = channel,
                        kind = ConversationKind.DIRECT_MESSAGE,
                        title = peer?.displayName ?: peerId,
                        peerUserId = peerId,
                        peerUsername = peer?.username,
                        unread = unread,
                        mentionCount = membership?.mentionCount ?: 0,
                    )
                }
                "O", "P", "" -> ConversationSummary(
                    channel = channel,
                    kind = ConversationKind.CHANNEL,
                    title = channel.displayName.ifBlank { channel.name },
                    unread = unread,
                    mentionCount = membership?.mentionCount ?: 0,
                )
                else -> null
            }
        }
        .toList()

    val channelsSection = summaries.asSequence()
        .filter { it.kind == ConversationKind.CHANNEL }
        .sortedBy { it.title.lowercase(Locale.ROOT) }
    val directSection = summaries.asSequence()
        .filter { it.kind == ConversationKind.DIRECT_MESSAGE }
        .sortedWith(
            compareByDescending<ConversationSummary> { it.lastPostAt }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    return (channelsSection + directSection).toList()
}

internal fun directMessagePeerId(channelName: String, currentUserId: String): String? =
    channelName.split("__", limit = 2)
        .takeIf { it.size == 2 }
        ?.firstOrNull { it.isNotBlank() && it != currentUserId }

internal fun directMessagePeerIds(
    channels: Collection<MattermostChannel>,
    currentUserId: String,
): Set<String> = channels.asSequence()
    .filter { it.type.equals("D", ignoreCase = true) }
    .mapNotNull { directMessagePeerId(it.name, currentUserId) }
    .toSet()

internal fun applyInactivePost(
    summary: ConversationSummary,
    post: MattermostPost,
    event: String,
    currentUserId: String,
): ConversationSummary {
    if (event != "posted" || post.deleteAt != 0L) return summary
    val lastPostAt = maxOf(summary.lastPostAt, post.createAt)
    return summary.copy(
        channel = summary.channel.copy(lastPostAt = lastPostAt),
        lastPostAt = lastPostAt,
        unread = summary.unread || post.userId != currentUserId,
    )
}
