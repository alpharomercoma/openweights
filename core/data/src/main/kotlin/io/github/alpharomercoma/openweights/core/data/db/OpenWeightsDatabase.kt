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
    ],
    version = 3,
    exportSchema = true,
)
abstract class OpenWeightsDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun usage(): UsageDao
    abstract fun reports(): ContentReportDao

    companion object {
        const val NAME = "openweights.db"

        /**
         * Adds attachments to messages.
         *
         * Written out rather than destroying and recreating: conversations are the whole
         * point of the app, and losing them to a schema change would be unforgivable for
         * data that exists nowhere else.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT")
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
