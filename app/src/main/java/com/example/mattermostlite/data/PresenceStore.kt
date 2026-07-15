package com.example.mattermostlite.data

import com.example.mattermostlite.model.MattermostUserStatus
import com.example.mattermostlite.model.UserPresence

/**
 * Keeps REST snapshots from overwriting WebSocket events received while the request was in flight.
 */
internal class PresenceStore {
    private val presences = mutableMapOf<String, UserPresence>()
    private val revisions = mutableMapOf<String, Long>()
    private var revision = 0L

    @Synchronized
    fun clear() {
        presences.clear()
        revisions.clear()
        revision = 0L
    }

    @Synchronized
    fun beginSnapshot(): Long = revision

    @Synchronized
    fun missingUserIds(userIds: Collection<String>): Set<String> =
        userIds.filterTo(mutableSetOf()) { it.isNotBlank() && it !in presences }

    @Synchronized
    fun isTracked(userId: String): Boolean = userId in presences

    @Synchronized
    fun applySnapshot(
        requestedUserIds: Collection<String>,
        statuses: Collection<MattermostUserStatus>,
        startedAtRevision: Long,
    ): Boolean {
        val byUserId = statuses.associateBy(MattermostUserStatus::userId)
        var changed = false
        requestedUserIds.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .forEach { userId ->
                if ((revisions[userId] ?: 0L) > startedAtRevision) return@forEach
                val next = UserPresence.fromMattermost(byUserId[userId]?.status.orEmpty())
                if (presences.put(userId, next) != next) changed = true
                revisions[userId] = startedAtRevision
            }
        return changed
    }

    @Synchronized
    fun applyEvent(userId: String, status: String): Boolean {
        if (userId.isBlank()) return false
        revision++
        revisions[userId] = revision
        val next = UserPresence.fromMattermost(status)
        return presences.put(userId, next) != next
    }

    @Synchronized
    fun snapshot(): Map<String, UserPresence> = presences.toMap()
}
