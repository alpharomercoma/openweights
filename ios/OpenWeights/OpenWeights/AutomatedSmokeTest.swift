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

        await engine.resetConversation()
        if await engine.supportsTools {
            session = await runToolTurn(
                "What is the exact current date and time? Answer with the exact value, not an approximation.",
                session: session,
                engine: engine,
                store: store
            )
        } else {
            log("tool-turn: skipped, model's chat template does not support tools (supportsTools=false)")
        }

        await runHubTurn()

        // Simulate "navigate away and back": a brand new SessionStore re-reading from disk,
        // exactly what relaunching the app produces, rather than reusing the in-memory one
        // that already has the answer.
        let reopened = await SessionStore()
        if let reloaded = await reopened.sessions.first(where: { $0.id == session.id }) {
            let turnsMatch = reloaded.turns.map(\.text) == session.turns.map(\.text)
            let samplerMatches = reloaded.sampler == session.sampler
            let statsMatch = reloaded.sessionTokens() != nil
                && reloaded.sessionTokens()?.inputTokens == session.sessionTokens()?.inputTokens
                && reloaded.sessionTokens()?.outputTokens == session.sessionTokens()?.outputTokens
            log(
                "reload: turnsMatch=\(turnsMatch) samplerMatches=\(samplerMatches) "
                    + "statsMatch=\(statsMatch) sessionTokens=\(String(describing: reloaded.sessionTokens())) "
                    + "turnCount=\(reloaded.turns.count)"
            )
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

        if let stats = await engine.lastStats, let index = current.turns.firstIndex(where: { $0.id == replyID }) {
            current.turns[index].promptTokens = stats.totalPromptTokens
            current.turns[index].cachedTokens = stats.cachedTokens
            current.turns[index].generatedTokens = stats.generatedTokens
            current.turns[index].prefillMillis = stats.prefillMillis
            current.turns[index].decodeMillis = stats.decodeMillis
            let reply = current.turns.last?.text.prefix(120) ?? ""
            log(
                "\(label): reply=\(reply) | promptTokens=\(stats.promptTokens) "
                    + "cachedTokens=\(stats.cachedTokens) generatedTokens=\(stats.generatedTokens) "
                    + "prefillMs=\(stats.prefillMillis) decodeMs=\(stats.decodeMillis)"
            )
        } else {
            log("\(label): no stats produced")
        }

        await store.save(current)
        return current
    }

    /// Exercises the same offer/call/execute/feed-back loop `ChatView.runTurnLoop` drives,
    /// standalone so this file has no SwiftUI/`@State` dependency, to prove tool calling works
    /// end to end -- not just that the prompt engineering compiles.
    private static func runToolTurn(
        _ text: String,
        session: ChatSession,
        engine: EngineClient,
        store: SessionStore
    ) async -> ChatSession {
        var current = session
        current.turns.append(PersistedTurn(role: "user", text: text))

        let instruction = ToolPrompting.instruction(anyTools: true) ?? ""
        var calledTool = false
        var toolResultText = ""

        for round in 0..<4 {
            let replyID = UUID()
            current.turns.append(PersistedTurn(id: replyID, role: "assistant", text: ""))
            let historyForModel = [PersistedTurn(role: "system", text: instruction)] + current.turns

            do {
                for try await piece in await engine.generate(history: historyForModel, sampler: current.sampler, tools: BuiltInTools.all) {
                    if let index = current.turns.firstIndex(where: { $0.id == replyID }) {
                        current.turns[index].text += piece
                    }
                }
            } catch {
                log("tool-turn: generate threw \(error.localizedDescription)")
                break
            }

            let calls = await engine.lastToolCalls
            guard !calls.isEmpty, round < 3 else { break }
            calledTool = true
            if let index = current.turns.firstIndex(where: { $0.id == replyID }), current.turns[index].text.isEmpty {
                current.turns.remove(at: index)
            }
            for call in calls {
                let result = BuiltInTools.execute(call)
                toolResultText = result
                current.turns.append(PersistedTurn(role: "tool", text: result, toolCallID: call.id))
            }
        }

        await store.save(current)
        let finalReply = current.turns.last(where: { $0.role == "assistant" })?.text.prefix(160) ?? ""
        log("tool-turn: calledTool=\(calledTool) toolResult=\(toolResultText) finalReply=\(finalReply)")
        return current
    }

    /// Milestone 6: proves `HuggingFaceHub`/`ModelDownloader` work against the real Hub from
    /// inside the Simulator, not just that they compile. Searches for a small model, lists
    /// its GGUF files, and downloads the smallest one (files are already sorted ascending) --
    /// capped to whatever is actually smallest for the query chosen, kept small on purpose so
    /// this finishes in a reasonable time.
    private static func runHubTurn() async {
        let hub = HuggingFaceHub()
        do {
            let results = try await hub.search(query: "smollm2 135m")
            log("hub: search returned \(results.count) repos, first=\(results.first?.id ?? "none")")
            guard let first = results.first else { return }

            let files = try await hub.files(inRepository: first.id)
            log("hub: \(first.id) has \(files.count) gguf files, smallest=\(files.first.map { "\($0.fileName) (\($0.sizeBytes) bytes)" } ?? "none")")
            guard let smallest = files.first(where: { !$0.isProjector }) else { return }

            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let destination = documents.appendingPathComponent("Models").appendingPathComponent(smallest.fileName)
            let url = await hub.downloadURL(repoId: first.id, path: smallest.path)
            let downloader = ModelDownloader()

            var finalState = "never finished"
            for await update in await downloader.download(from: url, to: destination, totalBytes: smallest.sizeBytes) {
                switch update {
                case .progress: continue
                case .finished(let finishedURL):
                    let size = (try? finishedURL.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? -1
                    finalState = "finished, bytesOnDisk=\(size), expected=\(smallest.sizeBytes)"
                case .failed(let error):
                    finalState = "failed: \(error.localizedDescription)"
                }
            }
            log("hub: download \(finalState)")
        } catch {
            log("hub: threw \(error.localizedDescription)")
        }
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
