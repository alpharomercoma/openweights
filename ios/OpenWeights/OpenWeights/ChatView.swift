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

    private func label(for role: String) -> String {
        switch role {
        case "user": return "You"
        case "tool": return "Tool result"
        default: return "Model"
        }
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
                            Text(label(for: turn.role))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(turn.text)
                                .font(turn.role == "tool" ? .caption.monospaced() : .body)
                                .foregroundStyle(turn.role == "tool" ? .secondary : .primary)
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

    /// A tool-calling turn goes model -> tool result(s) -> model again, same shape
    /// `TurnRunner` loops on Android. Capped at 4 rounds so a model that keeps calling tools
    /// forever (or calls one that keeps erroring) can't hang the chat indefinitely; Android's
    /// own tool loop carries an equivalent bound.
    private let maxToolRounds = 4

    private func send() {
        guard var current = session else { return }
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        prompt = ""

        current.turns.append(PersistedTurn(role: "user", text: text))
        if current.turns.count == 1 && current.title == "New chat" {
            current.title = String(text.prefix(40))
        }
        session = current
        sessionStore.save(current)

        generationTask = Task {
            await runTurnLoop()
            if let finished = session {
                sessionStore.save(finished)
            }
        }
    }

    /// Builds what actually gets sent to the engine: the tool-calling instruction
    /// (`ToolPrompting.instruction`) as a synthesized leading system turn, followed by the
    /// real transcript. The instruction is never itself persisted into `session.turns` --
    /// re-derived every call so a wording fix reaches every session immediately, the same
    /// problem `migratedToTheCurrentToolPrompt()` solves for Android's *stored* copy.
    /// Tools this session actually offers. Gated on `engine.supportsTools` -- a model whose
    /// chat template does not render tool definitions was never told about them by
    /// `common_chat_templates_apply`, so it free-runs text that only *looks* like a tool call
    /// (no grammar constrains it, and `Session::generate` never populates `tool_calls`). Live
    /// on-device check: both `Nanbeige4.2-3B` and `ling-3.0-tiny` emitted an unparsed
    /// `<tool_call>...</tool_call>` string as plain reply text once offered a tool anyway --
    /// offering tools to a template that cannot render them is worse than not offering them,
    /// so this must gate on the real capability, not just "the app has a built-in tool."
    private var offeredTools: [ToolDefinition] {
        engine.supportsTools ? BuiltInTools.all : []
    }

    private func engineHistory(_ turns: [PersistedTurn]) -> [PersistedTurn] {
        guard let instruction = ToolPrompting.instruction(anyTools: !offeredTools.isEmpty) else {
            return turns
        }
        return [PersistedTurn(role: "system", text: instruction)] + turns
    }

    private func runTurnLoop() async {
        for round in 0..<maxToolRounds {
            guard let current = session else { return }
            let replyID = UUID()
            appendTurn(PersistedTurn(id: replyID, role: "assistant", text: ""))

            let historyForModel = engineHistory(current.turns)
            let sampler = current.sampler

            do {
                for try await piece in engine.generate(history: historyForModel, sampler: sampler, tools: offeredTools) {
                    appendToReply(piece, replyID: replyID)
                }
            } catch is CancellationError {
                return
            } catch {
                appendToReply("\n[error: \(error.localizedDescription)]", replyID: replyID)
                return
            }

            let calls = engine.lastToolCalls
            guard !calls.isEmpty, round < maxToolRounds - 1 else { return }

            // An empty reply alongside a tool call is normal -- the model decided to act
            // instead of narrating first -- so drop the placeholder bubble rather than
            // leaving a blank "Model" turn sitting in the transcript.
            if let current = session, let index = current.turns.firstIndex(where: { $0.id == replyID }),
               current.turns[index].text.isEmpty {
                removeTurn(id: replyID)
            }

            for call in calls {
                let result = BuiltInTools.execute(call)
                appendTurn(PersistedTurn(role: "tool", text: result, toolCallID: call.id))
            }
        }
    }

    private func appendTurn(_ turn: PersistedTurn) {
        guard var current = session else { return }
        current.turns.append(turn)
        session = current
    }

    private func removeTurn(id: UUID) {
        guard var current = session else { return }
        current.turns.removeAll { $0.id == id }
        session = current
    }

    private func appendToReply(_ piece: String, replyID: UUID) {
        guard var current = session else { return }
        guard let index = current.turns.firstIndex(where: { $0.id == replyID }) else { return }
        current.turns[index].text += piece
        session = current
    }
}
