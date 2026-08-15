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
}

private fun SQLiteConnection.textAt(sql: String): String = prepare(sql).use { statement ->
    check(statement.step()) { "no row for: $sql" }
    statement.getText(0)
}

private fun SQLiteConnection.intAt(sql: String): Int = prepare(sql).use { statement ->
    check(statement.step()) { "no row for: $sql" }
    statement.getInt(0)
}
