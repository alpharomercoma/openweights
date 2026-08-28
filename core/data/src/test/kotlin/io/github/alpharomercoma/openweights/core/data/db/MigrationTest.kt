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

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What survives an upgrade.
 *
 * Conversations exist nowhere but this device, so a migration that loses one is the worst
 * thing this app can do, and it is the one failure that cannot be found by running the app:
 * a fresh install creates the newest schema directly and never executes a migration at all.
 * These run them, against the checked-in schemas, which is also what proves the SQL written
 * by hand still lands on the shape Room expects.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        // A real file rather than memory: a migration is a thing that happens to a database
        // on disk, and an in-memory one is created at the newest version and never migrated.
        // The rule deletes it before each test.
        file = File.createTempFile("openweights-migration", ".db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = OpenWeightsDatabase::class,
    )

    private val migrations = arrayOf(
        OpenWeightsDatabase.MIGRATION_1_2,
        OpenWeightsDatabase.MIGRATION_2_3,
        OpenWeightsDatabase.MIGRATION_3_4,
        OpenWeightsDatabase.MIGRATION_4_5,
        OpenWeightsDatabase.MIGRATION_5_6,
        OpenWeightsDatabase.MIGRATION_6_7,
        OpenWeightsDatabase.MIGRATION_7_8,
        OpenWeightsDatabase.MIGRATION_8_9,
    )

    @Test
    fun `a conversation written at version one arrives intact at version four`() {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id, title, modelName, createdAt, updatedAt, compactionThroughIndex) " +
                    "VALUES (1, 'About Ada', 'qwen', 10, 20, -1)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, role, text, createdAt) " +
                    "VALUES (1, 1, 'user', 'Who was Ada Lovelace?', 30)",
            )
        }

        helper.runMigrationsAndValidate(4, migrations.toList()).use { db ->
            assertThat(db.textAt("SELECT title FROM conversations WHERE id = 1"))
                .isEqualTo("About Ada")
            assertThat(db.textAt("SELECT text FROM messages WHERE id = 1"))
                .isEqualTo("Who was Ada Lovelace?")
        }
    }

    @Test
    fun `a summary written before the log existed becomes the first version of it`() {
        // The one column that mattered. A conversation folded under version three has its
        // summary in `conversations` and nowhere else, and the migration used to create the
        // log without putting that summary in it: the history came back empty, and the next
        // fold overwrote the column, so the summary the app had been using was gone and
        // unrecoverable by the feature written to make it recoverable.
        helper.createDatabase(3).use { db ->
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id, title, modelName, createdAt, updatedAt, compactionSummary, " +
                    "compactionThroughIndex) " +
                    "VALUES (1, 'Long one', 'qwen', 10, 20, 'They discussed Ada.', 6)",
            )
        }

        helper.runMigrationsAndValidate(4, migrations.toList()).use { db ->
            assertThat(db.textAt("SELECT summary FROM compactions WHERE conversationId = 1"))
                .isEqualTo("They discussed Ada.")
            assertThat(db.intAt("SELECT throughIndex FROM compactions WHERE conversationId = 1"))
                .isEqualTo(6)
            assertThat(db.intAt("SELECT version FROM compactions WHERE conversationId = 1"))
                .isEqualTo(1)
            // And the conversation points at it, so the next fold appends as version two
            // rather than starting the numbering again on top of what was there.
            assertThat(db.intAt("SELECT compactionHeadId FROM conversations WHERE id = 1"))
                .isEqualTo(db.intAt("SELECT id FROM compactions WHERE conversationId = 1"))
        }
    }

    @Test
    fun `a conversation that was never folded gets no summary invented for it`() {
        helper.createDatabase(3).use { db ->
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id, title, modelName, createdAt, updatedAt, compactionThroughIndex) " +
                    "VALUES (1, 'Short one', 'qwen', 10, 20, -1)",
            )
        }

        helper.runMigrationsAndValidate(4, migrations.toList()).use { db ->
            assertThat(db.intAt("SELECT count(*) FROM compactions")).isEqualTo(0)
        }
    }

    @Test
    fun `a gallery from before generation was removed is dropped without touching anything else`() {
        // The table existed under version six. A conversation and a watch written alongside
        // it have to come through the migration that removes it untouched.
        helper.createDatabase(6).use { db ->
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id, title, modelName, createdAt, updatedAt, compactionThroughIndex) " +
                    "VALUES (1, 'About Ada', 'qwen', 10, 20, -1)",
            )
            db.execSQL(
                "INSERT INTO watches " +
                    "(id, task, everyMinutes, state, createdAt, runs, consecutiveFailures) " +
                    "VALUES (1, 'check the tides', 15, 'ACTIVE', 5, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO gallery " +
                    "(path, mediaType, modality, prompt, negativePrompt, bundleId, " +
                    "bundleName, createdAt, totalMillis, backend, sizeBytes, isFavourite) " +
                    "VALUES ('/pictures/1.png', 'image/png', 'IMAGE', 'a lighthouse', '', " +
                    "'sd15', 'Stable Diffusion 1.5', 100, 9000, 'OpenCL', 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(7, migrations.toList()).use { db ->
            assertThat(db.textAt("SELECT title FROM conversations WHERE id = 1"))
                .isEqualTo("About Ada")
            assertThat(db.textAt("SELECT task FROM watches WHERE id = 1"))
                .isEqualTo("check the tides")
            assertThat(
                db.intAt(
                    "SELECT count(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'gallery'",
                ),
            ).isEqualTo(0)
        }
    }

    @Test
    fun `a usage row from before decode time was split out keeps its totals and reads as unmeasured`() {
        // decodeMs did not exist at version seven, so every row already on a device is
        // exactly this shape: a real inferenceMs total with nothing to say about how much
        // of it was decode. The calibration query is what has to treat that as "no
        // measurement" rather than "measured at zero tokens a second" — this only checks
        // that the migration itself hands it a real zero to do that with, not an error.
        helper.createDatabase(7).use { db ->
            db.execSQL(
                "INSERT INTO usage_ledger " +
                    "(day, modelName, promptTokens, generatedTokens, inferenceMs, replies) " +
                    "VALUES (100, 'qwen', 500, 200, 9000, 3)",
            )
        }

        helper.runMigrationsAndValidate(8, migrations.toList()).use { db ->
            assertThat(db.intAt("SELECT generatedTokens FROM usage_ledger WHERE day = 100"))
                .isEqualTo(200)
            assertThat(db.intAt("SELECT inferenceMs FROM usage_ledger WHERE day = 100"))
                .isEqualTo(9000)
            assertThat(db.intAt("SELECT decodeMs FROM usage_ledger WHERE day = 100"))
                .isEqualTo(0)
            assertThat(db.intAt("SELECT decodeTokens FROM usage_ledger WHERE day = 100"))
                .isEqualTo(0)
        }
    }

    @Test
    fun `a usage row from before prefill time was split out keeps its totals and reads as unmeasured`() {
        // Same shape as decodeMs one version earlier, checked separately because it is a
        // different migration touching a table that by version eight already has decodeMs
        // and decodeTokens on it — this is the one that has to add prefillMs and
        // prefillTokens alongside them without disturbing either.
        helper.createDatabase(8).use { db ->
            db.execSQL(
                "INSERT INTO usage_ledger " +
                    "(day, modelName, promptTokens, generatedTokens, inferenceMs, replies, " +
                    "decodeMs, decodeTokens) " +
                    "VALUES (100, 'qwen', 500, 200, 9000, 3, 7000, 199)",
            )
        }

        helper.runMigrationsAndValidate(9, migrations.toList()).use { db ->
            assertThat(db.intAt("SELECT decodeMs FROM usage_ledger WHERE day = 100"))
                .isEqualTo(7000)
            assertThat(db.intAt("SELECT decodeTokens FROM usage_ledger WHERE day = 100"))
                .isEqualTo(199)
            assertThat(db.intAt("SELECT prefillMs FROM usage_ledger WHERE day = 100"))
                .isEqualTo(0)
            assertThat(db.intAt("SELECT prefillTokens FROM usage_ledger WHERE day = 100"))
                .isEqualTo(0)
        }
    }
}

private fun SQLiteConnection.textAt(sql: String): String = prepare(sql).use { statement ->
    check(statement.step()) { "no row for: $sql" }
    statement.getText(0)
}

private fun SQLiteConnection.intAt(sql: String): Int = prepare(sql).use { statement ->
    check(statement.step()) { "no row for: $sql" }
    statement.getInt(0)
}
