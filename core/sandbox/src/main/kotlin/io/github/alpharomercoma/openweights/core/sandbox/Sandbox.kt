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

package io.github.alpharomercoma.openweights.core.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The envelope a run comes back in, so one string carries both halves of the answer. */
private const val FAILED_MARK = "!"
private const val OK_MARK = "."

internal fun ScriptResult.encode(): String = (if (failed) FAILED_MARK else OK_MARK) + output

internal fun String.decoded(): ScriptResult =
    ScriptResult(output = drop(1), failed = startsWith(FAILED_MARK))

/**
 * Runs a script somewhere it can do no harm, and waits for the answer.
 *
 * Binds for the call and unbinds after. A held connection would keep a second process alive
 * for the whole session to save a few milliseconds on a tool nobody calls twice in a row,
 * on a device where those milliseconds are competing with a model that wants every page of
 * memory it can have.
 *
 * Two clocks, deliberately. The interpreter stops itself at [millis] from the inside, and
 * this waits a little longer from the outside. If the inner one works the outer never
 * fires; if the process is killed for memory, or the binder never answers, the outer one is
 * the only thing that ends the turn instead of hanging it.
 */
@Singleton
class Sandbox @Inject constructor(@param:ApplicationContext private val context: Context) {
    suspend fun run(
        source: String,
        inputsJson: String = "{}",
        millis: Long = DEFAULT_MILLIS,
        memoryBytes: Long = DEFAULT_MEMORY_BYTES,
        outputLimit: Int = DEFAULT_OUTPUT_CHARS,
    ): ScriptResult = withContext(Dispatchers.IO) {
        val connection = Connection()
        val intent = Intent(context, ScriptService::class.java)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            runCatching { context.unbindService(connection) }
            return@withContext ScriptResult(CANNOT_START, failed = true)
        }

        try {
            // Null is the outer clock winning, which only happens when the process itself
            // went away: a script that merely ran long stops itself from the inside and
            // comes back as an ordinary failure with something in it to read.
            withTimeoutOrNull(millis + GRACE_MILLIS) {
                val runner = connection.awaitBinder()
                // The binder throws if the far side died, and for an isolated process that
                // is an ordinary Tuesday: Android reclaims it under memory pressure, and a
                // model holding a couple of gigabytes supplies plenty of that.
                runCatching {
                    runner.run(source, inputsJson, memoryBytes, millis, outputLimit)
                }.map { it.decoded() }.getOrElse { ScriptResult(STOPPED, failed = true) }
            } ?: ScriptResult(STOPPED, failed = true)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private class Connection : ServiceConnection {
        private var binder: IBinder? = null
        private var waiting: ((IBinder) -> Unit)? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            service ?: return
            binder = service
            waiting?.invoke(service)
            waiting = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }

        suspend fun awaitBinder(): IScriptRunner = suspendCancellableCoroutine { continuation ->
            val ready = binder
            if (ready != null) {
                continuation.resume(IScriptRunner.Stub.asInterface(ready))
            } else {
                waiting = { continuation.resume(IScriptRunner.Stub.asInterface(it)) }
                continuation.invokeOnCancellation { waiting = null }
            }
        }
    }

    private companion object {
        const val CANNOT_START = "The sandbox could not be started, so nothing was run."

        /**
         * Said when the process died rather than the script.
         *
         * Kept separate from the interpreter's own timeout message because they mean
         * different things to whoever reads the log: one is a script that would not stop,
         * the other is Android reclaiming a process while a model held the memory.
         */
        const val STOPPED = "The sandbox stopped before the script finished."

        /** Long enough for real work, short enough that a wedged turn is still a turn. */
        const val DEFAULT_MILLIS = 2_000L

        /** Time for a binder round trip and a process start, on top of the script's own. */
        const val GRACE_MILLIS = 3_000L

        /** A phone already holding gigabytes of weights; this is scratch space, not a heap. */
        const val DEFAULT_MEMORY_BYTES = 32L * 1024 * 1024

        /** What comes back has to fit a context measured in thousands of tokens. */
        const val DEFAULT_OUTPUT_CHARS = 2_000
    }
}
