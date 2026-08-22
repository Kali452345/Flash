package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat message, local-first. [localId] is the client-generated UUID and the stable key for
 * dedup on insert (idempotent sync). Tombstoning ([deletedAt]) never removes rows so history
 * pagination stays stable.
 */
@Entity(
    tableName = "messages",
    indices = [
        // Composite seek index matching the keyset pagination order (C1.3):
        // WHERE conversationId = ? AND (sentAt < :c OR sentAt = :c AND localId < :id)
        // ORDER BY sentAt DESC, localId DESC.
        Index(value = ["conversationId", "sentAt", "localId"]),
        // Stand-alone time index serves cross-conversation scans (retention pruning C1.6).
        Index(value = ["sentAt"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val localId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String?,
    val text: String,
    val sentAt: Long,
    val status: String,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
)
