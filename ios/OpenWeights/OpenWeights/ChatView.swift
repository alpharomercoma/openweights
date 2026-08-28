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

/// One conversation: import/load already happened in `ModelPickerView` before this screen
/// was ever pushed, so `session.modelPath` is always already resolved. Every turn -- the
/// user's message and the model's reply -- is written back to `SessionStore` as soon as it
/// completes, so navigating away and back (this milestone's original persistence bug report,
/// on Android) shows the same transcript and the same last-turn stats line, not a blank one.
struct ChatView: View {
    let sessionID: UUID

    @EnvironmentObject private var sessionStore: SessionStore
    @StateObject private var engine = EngineClient()
    @State private var session: ChatSession?
    @State private var prompt = ""
    @State private var generationTask: Task<Void, Never>?
    @State private var isSamplerSheetPresented = false

    var body: some View {
        Group {
            if let session {
                VStack(spacing: 0) {
                    statusBar(session)
                    Divider()
                    transcript(session)
                    Divider()
                    composer
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(session?.title ?? "Chat")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    isSamplerSheetPresented = true
                } label: {
                    Label("Sampler", systemImage: "slider.horizontal.3")
                }
            }
        }
        .sheet(isPresented: $isSamplerSheetPresented) {
            if session != nil {
                SamplerSettingsView(settings: Binding(
                    get: { session?.sampler ?? SamplerSettings() },
                    set: { newValue in
                        session?.sampler = newValue
                        if let session { sessionStore.save(session) }
                    }
                ))
            }
        }
        .task {
            guard session == nil else { return }
            guard let loaded = sessionStore.sessions.first(where: { $0.id == sessionID }) else { return }
            session = loaded
            await engine.loadModel(atPath: loaded.modelPath)
        }
    }

    private func statusBar(_ session: ChatSession) -> some View {
        HStack {
            if engine.isLoadingModel {
                ProgressView().controlSize(.small)
                Text("Loading model…")
            } else if let error = engine.loadError {
                Text(error).foregroundStyle(.red).lineLimit(1)
            } else if engine.isLoaded {
                Text((session.modelPath as NSString).lastPathComponent).foregroundStyle(.secondary)
            } else {
                Text("Not loaded").foregroundStyle(.secondary)
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

    private func transcript(_ session: ChatSession) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(session.turns) { turn in
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
            .onChange(of: session.turns.last?.text) { _ in
                if let lastID = session.turns.last?.id {
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

    private func send() {
        guard var current = session else { return }
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        prompt = ""

        current.turns.append(PersistedTurn(role: "user", text: text))
        if current.turns.count == 1 {
            current.title = String(text.prefix(40))
        }
        let replyID = UUID()
        current.turns.append(PersistedTurn(id: replyID, role: "assistant", text: ""))
        session = current
        sessionStore.save(current)

        let historyForModel = current.turns
        let sampler = current.sampler

        generationTask = Task {
            do {
                for try await piece in engine.generate(history: historyForModel, sampler: sampler) {
                    appendToReply(piece, replyID: replyID)
                }
            } catch is CancellationError {
                // Stop button: partial text already streamed in stays on screen.
            } catch {
                appendToReply("\n[error: \(error.localizedDescription)]", replyID: replyID)
            }
            if let finished = session {
                sessionStore.save(finished)
            }
        }
    }

    private func appendToReply(_ piece: String, replyID: UUID) {
        guard var current = session else { return }
        guard let index = current.turns.firstIndex(where: { $0.id == replyID }) else { return }
        current.turns[index].text += piece
        session = current
    }
}
