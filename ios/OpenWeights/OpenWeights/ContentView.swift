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

import SwiftUI
import UniformTypeIdentifiers

private struct ChatTurn: Identifiable {
    let id = UUID()
    let role: String
    var text: String
}

/// Milestone 2's vertical slice: import a local GGUF, load/unload it, send a prompt, stream
/// the reply, stop mid-generation, and show the `↑input ↓output · CHn%` line a second turn
/// through the same session should shrink (`cachedTokens` reusing the first turn's KV cache).
/// Conversation persistence, model download, and tool calling are deliberately not here yet --
/// see the plan this milestone belongs to.
struct ContentView: View {
    @StateObject private var engine = EngineClient()
    @State private var turns: [ChatTurn] = []
    @State private var prompt = ""
    @State private var isImporterPresented = false
    @State private var modelURL: URL?
    @State private var generationTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                statusBar
                Divider()
                transcript
                Divider()
                composer
            }
            .navigationTitle("OpenWeights")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Import GGUF") { isImporterPresented = true }
                        .disabled(engine.isLoadingModel)
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button("Reset") {
                        engine.resetConversation()
                        turns.removeAll()
                    }
                    .disabled(!engine.isLoaded)
                }
            }
        }
        .fileImporter(
            isPresented: $isImporterPresented,
            allowedContentTypes: [UTType(filenameExtension: "gguf") ?? .data],
            onCompletion: handleImport
        )
        .task { await runAutomatedSmokeTestIfConfigured() }
    }

    /// Milestone 2's acceptance bar is "a one-turn demo is too weak": a second turn through
    /// the same session must show nonzero `cachedTokens`, and an explicit reset must zero it
    /// again. Driving that by hand through the Simulator's file picker isn't repeatable, so
    /// when `OW_AUTO_MODEL_PATH` is set (see build-and-smoke-test.sh) this runs the same three
    /// turns unattended and drops the stats each turn produced into the app's Documents
    /// directory, where the host-side script that launched it can read them back.
    private func runAutomatedSmokeTestIfConfigured() async {
        guard let path = ProcessInfo.processInfo.environment["OW_AUTO_MODEL_PATH"] else { return }
        modelURL = URL(fileURLWithPath: path)
        await engine.loadModel(atPath: path)
        guard engine.isLoaded else {
            writeSmokeTestLog("load failed: \(engine.loadError ?? "unknown")")
            return
        }

        await runAutomatedTurn("Name three planets in one short sentence.", label: "turn1")
        await runAutomatedTurn("What was the first planet you named?", label: "turn2")
        engine.resetConversation()
        await runAutomatedTurn("Name three planets in one short sentence.", label: "turn3-after-reset")
    }

    private func runAutomatedTurn(_ text: String, label: String) async {
        turns.append(ChatTurn(role: "user", text: text))
        let replyIndex = turns.count
        turns.append(ChatTurn(role: "assistant", text: ""))
        do {
            for try await piece in engine.generate(prompt: text) {
                turns[replyIndex].text += piece
            }
        } catch {
            turns[replyIndex].text += "\n[error: \(error.localizedDescription)]"
        }
        if let stats = engine.lastStats {
            writeSmokeTestLog(
                "\(label): reply=\(turns[replyIndex].text.prefix(120)) | "
                    + "promptTokens=\(stats.promptTokens) cachedTokens=\(stats.cachedTokens) "
                    + "generatedTokens=\(stats.generatedTokens) prefillMs=\(stats.prefillMillis) "
                    + "decodeMs=\(stats.decodeMillis)"
            )
        } else {
            writeSmokeTestLog("\(label): no stats produced")
        }
    }

    private func writeSmokeTestLog(_ line: String) {
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

    private var statusBar: some View {
        HStack {
            if engine.isLoadingModel {
                ProgressView().controlSize(.small)
                Text("Loading model…")
            } else if let error = engine.loadError {
                Text(error).foregroundStyle(.red).lineLimit(1)
            } else if engine.isLoaded {
                Text(modelURL?.lastPathComponent ?? "Model loaded").foregroundStyle(.secondary)
            } else {
                Text("No model loaded").foregroundStyle(.secondary)
            }
            Spacer()
            if let stats = engine.lastStats {
                Text(statsLine(stats)).font(.caption.monospaced()).foregroundStyle(.secondary)
            }
        }
        .font(.subheadline)
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private func statsLine(_ stats: TurnStats) -> String {
        let hitPercent = Int((stats.cacheHitRate * 100).rounded())
        return "↑\(stats.totalPromptTokens) ↓\(stats.generatedTokens) · CH\(hitPercent)%"
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(turns) { turn in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(turn.role == "user" ? "You" : "Model")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(turn.text)
                        }
                        .id(turn.id)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding()
            }
            .onChange(of: turns.last?.text) { _ in
                if let lastID = turns.last?.id {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
        }
    }

    private var composer: some View {
        HStack {
            TextField("Message", text: $prompt, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .disabled(!engine.isLoaded)
            if engine.isGenerating {
                Button("Stop") { generationTask?.cancel() }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
            } else {
                Button("Send") { send() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!engine.isLoaded || prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding()
    }

    private func handleImport(_ result: Result<URL, Error>) {
        guard case .success(let sourceURL) = result else { return }

        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessed { sourceURL.stopAccessingSecurityScopedResource() } }

        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let destination = documents.appendingPathComponent(sourceURL.lastPathComponent)

        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: sourceURL, to: destination)
            modelURL = destination
            turns.removeAll()
            Task { await engine.loadModel(atPath: destination.path) }
        } catch {
            engine.resetConversation()
        }
    }

    private func send() {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        prompt = ""

        turns.append(ChatTurn(role: "user", text: text))
        let replyIndex = turns.count
        turns.append(ChatTurn(role: "assistant", text: ""))

        generationTask = Task {
            do {
                for try await piece in engine.generate(prompt: text) {
                    if replyIndex < turns.count {
                        turns[replyIndex].text += piece
                    }
                }
            } catch is CancellationError {
                // Stop button: partial text already streamed in stays on screen.
            } catch {
                if replyIndex < turns.count {
                    turns[replyIndex].text += "\n[error: \(error.localizedDescription)]"
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
