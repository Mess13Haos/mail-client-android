package com.example.mailclient

internal data class MailNotificationTarget(
    val email: String,
    val uid: Long,
    val folder: String
)

internal object MailNotificationState {
    const val CHECK_INTERVAL_MINUTES = 15L

    internal data class Reconciliation(
        val notificationsToShow: List<ImapConnector.NewMailInfo>,
        val uidsToDismiss: Set<Long>,
        val notifiedUids: Set<Long>,
        val seenMessageIds: Set<String>
    )

    fun target(email: String?, uid: Long, folder: String?): MailNotificationTarget? {
        if (email.isNullOrBlank() || uid <= 0L) return null
        return MailNotificationTarget(email, uid, folder?.takeIf { it.isNotBlank() } ?: "INBOX")
    }

    fun notificationId(email: String, uid: Long, folderName: String = "INBOX", uidValidity: Long = 0L): Int {
        val key = "${email.trim().lowercase()}|${folderName.trim().lowercase()}|$uidValidity|$uid"
        return key.hashCode()
    }

    fun reconcile(
        previouslyNotifiedUids: Set<Long>,
        seenMessageIds: Set<String> = emptySet(),
        unreadMessages: List<ImapConnector.NewMailInfo>?,
        newlyReceivedMessages: List<ImapConnector.NewMailInfo>?
    ): Reconciliation {
        val unreadUids = unreadMessages?.mapTo(mutableSetOf()) { it.uid }
        val retainedUids = unreadUids?.let(previouslyNotifiedUids::intersect) ?: previouslyNotifiedUids

        val candidates = (unreadMessages.orEmpty() + newlyReceivedMessages.orEmpty())
            .distinctBy { it.stableId() }
            .filterNot { it.uid in previouslyNotifiedUids || it.stableId() in seenMessageIds }

        val notifiedUids = retainedUids + candidates.map { it.uid }
        val updatedSeenIds = seenMessageIds + candidates.map { it.stableId() }

        return Reconciliation(
            notificationsToShow = candidates,
            uidsToDismiss = unreadUids?.let { previouslyNotifiedUids - it } ?: emptySet(),
            notifiedUids = notifiedUids,
            seenMessageIds = updatedSeenIds
        )
    }

    fun uidsReadSinceLastCheck(notifiedUids: Set<Long>, unreadUids: Set<Long>): Set<Long> =
        notifiedUids - unreadUids

    fun stillUnread(notifiedUids: Set<Long>, unreadUids: Set<Long>): Set<Long> =
        notifiedUids intersect unreadUids
}
