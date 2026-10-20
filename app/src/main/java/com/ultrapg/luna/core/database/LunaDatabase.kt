/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelUsed: String = "",
    val languageCode: String = "en"
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val toolCallsSummary: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "assistant_memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val key: String,
    val value: String,
    val category: String = "general",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt, modelUsed = :model WHERE id = :sessionId")
    suspend fun updateSessionMetadata(sessionId: Long, title: String, updatedAt: Long, model: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM assistant_memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM assistant_memories ORDER BY updatedAt DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM assistant_memories WHERE `key` LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Query("SELECT * FROM assistant_memories WHERE LOWER(`key`) = LOWER(:key) LIMIT 1")
    suspend fun findByKey(key: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("DELETE FROM assistant_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM assistant_memories WHERE LOWER(`key`) = LOWER(:key)")
    suspend fun deleteMemoryByKey(key: String)

    @Query("DELETE FROM assistant_memories")
    suspend fun clearAllMemories()
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        MemoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LunaDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var instance: LunaDatabase? = null

        fun getInstance(context: Context): LunaDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LunaDatabase::class.java,
                    "luna_assistant.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
