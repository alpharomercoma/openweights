/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.alpharomercoma.openweights.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Everything the app remembers. Never leaves the device and is excluded from backups. */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        UsageEntity::class,
        ContentReportEntity::class,
        CompactionEntity::class,
        WatchEntity::class,
        WatchRunEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class OpenWeightsDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun usage(): UsageDao
    abstract fun reports(): ContentReportDao
    abstract fun compactions(): CompactionDao
    abstract fun watches(): WatchDao

    companion object {
        const val NAME = "openweights.db"

        /** Drops the gallery: image/speech generation was removed. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS gallery")
            }
        }

        /**
         * Adds attachments to messages.
         *
         * Written out rather than destroying and recreating: conversations are the whole
         * point of the app, and losing them to a schema change would be unforgivable for
         * data that exists nowhere else.
         */
        /**
         * Adds watches and their run history.
         *
         * Two new tables and nothing touched, so this is the safe kind of migration. The
         * runs table cascades on delete, which is what keeps a cancelled watch from leaving
         * an orphaned log behind.
         */
        /**
         * Adds the gallery.
         *
         * One new table and nothing touched. The unique index on the path is the load
         * bearing part: a generation writes its file and then records it, and a process
         * that dies between the two is resumed by a retry that writes the same path again.
         * Without the constraint the same picture appears twice and deleting one of them
         * leaves a row pointing at nothing.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gallery (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        path TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        modality TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        negativePrompt TEXT NOT NULL DEFAULT '',
                        bundleId TEXT NOT NULL,
                        bundleName TEXT NOT NULL,
                        seed INTEGER,
                        createdAt INTEGER NOT NULL,
                        totalMillis INTEGER NOT NULL,
                        backend TEXT NOT NULL,
                        width INTEGER,
                        height INTEGER,
                        durationMillis INTEGER,
                        sizeBytes INTEGER NOT NULL DEFAULT 0,
                        isFavourite INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_gallery_path ON gallery(path)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_gallery_createdAt ON gallery(createdAt)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        task TEXT NOT NULL,
                        everyMinutes INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastRunAt INTEGER,
                        lastSummary TEXT,
                        runs INTEGER NOT NULL DEFAULT 0,
                        consecutiveFailures INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watch_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        watchId INTEGER NOT NULL,
                        at INTEGER NOT NULL,
                        outcome TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        FOREIGN KEY(watchId) REFERENCES watches(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watch_runs_watchId ON watch_runs(watchId)",
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT")
            }
        }

        /**
         * Turns the one summary a conversation had into an append-only log with a pointer.
         *
         * The old columns stay. A conversation folded before this arrives has its summary in
         * them and nowhere else, and dropping them to tidy up would lose exactly the thing
         * this feature exists to keep. They are still written, and read only when a
         * conversation has no head row yet.
         *
         * The summary that was already there becomes version one of the log. Creating the
         * table empty and leaving it that way looked harmless, because the old columns still
         * answered every read. It was not: the next fold appends as version one on top of the
         * numbering and overwrites those columns, so the summary the app had been using
         * disappeared, from the feature whose entire purpose is that it should not.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS compactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        throughIndex INTEGER NOT NULL,
                        modelName TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES conversations(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_compactions_conversationId " +
                        "ON compactions(conversationId)",
                )
                db.execSQL("ALTER TABLE conversations ADD COLUMN compactionHeadId INTEGER")
                db.execSQL(
                    """
                    INSERT INTO compactions
                        (conversationId, version, summary, throughIndex, modelName, createdAt)
                    SELECT id, 1, compactionSummary, compactionThroughIndex, modelName, updatedAt
                    FROM conversations
                    WHERE compactionSummary IS NOT NULL
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE conversations SET compactionHeadId = (
                        SELECT c.id FROM compactions c WHERE c.conversationId = conversations.id
                    )
                    WHERE compactionSummary IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

        /** Adds the table behind the in-app report action. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modelName TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        replyText TEXT NOT NULL,
                        note TEXT NOT NULL,
                        reportedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
