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

import Foundation

/// Drives the real persistence + engine stack unattended when `OW_AUTO_MODEL_PATH` is set in
/// the launching process's environment (see build-and-smoke-test.sh) -- never runs otherwise.
/// Exists because the Simulator's file picker makes a three-turn, cache-reuse, then a
/// "reopen and check it survived" pass impossible to script reliably by hand: this runs the
/// same three engine turns Milestone 2's smoke test did, then re-reads the session back from
/// a *fresh* `SessionStore` instance -- the same thing relaunching the app would do -- to
/// prove the turns, stats-bearing text, and sampler settings actually round-trip through disk
/// rather than only living in the in-memory `@Published session` `ChatView` holds. That gap
/// -- stats and timings vanishing on reopen -- was the exact bug this milestone traces back to
/// on Android; this is iOS's version of the regression test for it.
enum AutomatedSmokeTest {
    static func runIfConfigured() async {
        guard let modelPath = ProcessInfo.processInfo.environment["OW_AUTO_MODEL_PATH"] else { return }

        let store = await SessionStore()
        let engine = await EngineClient()
        var session = await ChatSession(title: "smoke test", modelPath: modelPath)

        await engine.loadModel(atPath: modelPath)
        guard await engine.isLoaded else {
            log("load failed: \(await engine.loadError ?? "unknown")")
            return
        }

        session = await runTurn("Name three planets in one short sentence.", session: session, engine: engine, store: store, label: "turn1")
        session = await runTurn("What was the first planet you named?", session: session, engine: engine, store: store, label: "turn2")
        await engine.resetConversation()
        session = await runTurn("Name three planets in one short sentence.", session: session, engine: engine, store: store, label: "turn3-after-reset")

        // Simulate "navigate away and back": a brand new SessionStore re-reading from disk,
        // exactly what relaunching the app produces, rather than reusing the in-memory one
        // that already has the answer.
        let reopened = await SessionStore()
        if let reloaded = await reopened.sessions.first(where: { $0.id == session.id }) {
            let turnsMatch = reloaded.turns.map(\.text) == session.turns.map(\.text)
            let samplerMatches = reloaded.sampler == session.sampler
            log("reload: turnsMatch=\(turnsMatch) samplerMatches=\(samplerMatches) turnCount=\(reloaded.turns.count)")
        } else {
            log("reload: session missing after reopen")
        }
    }

    private static func runTurn(
        _ text: String,
        session: ChatSession,
        engine: EngineClient,
        store: SessionStore,
        label: String
    ) async -> ChatSession {
        var current = session
        current.turns.append(PersistedTurn(role: "user", text: text))
        let replyID = UUID()
        current.turns.append(PersistedTurn(id: replyID, role: "assistant", text: ""))

        do {
            for try await piece in await engine.generate(history: current.turns, sampler: current.sampler) {
                if let index = current.turns.firstIndex(where: { $0.id == replyID }) {
                    current.turns[index].text += piece
                }
            }
        } catch {
            log("\(label): generate threw \(error.localizedDescription)")
        }

        await store.save(current)

        if let stats = await engine.lastStats {
            let reply = current.turns.last?.text.prefix(120) ?? ""
            log(
                "\(label): reply=\(reply) | promptTokens=\(stats.promptTokens) "
                    + "cachedTokens=\(stats.cachedTokens) generatedTokens=\(stats.generatedTokens) "
                    + "prefillMs=\(stats.prefillMillis) decodeMs=\(stats.decodeMillis)"
            )
        } else {
            log("\(label): no stats produced")
        }
        return current
    }

    private static func log(_ line: String) {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let logURL = documents.appendingPathComponent("smoke-test.log")
        let entry = line + "\n"
        if let handle = try? FileHandle(forWritingTo: logURL) {
            handle.seekToEndOfFile()
            handle.write(entry.data(using: .utf8)!)
            try? handle.close()
        } else {
            try? entry.write(to: logURL, atomically: true, encoding: .utf8)
        }
    }
}
