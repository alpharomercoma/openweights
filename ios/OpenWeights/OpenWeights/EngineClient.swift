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

/// Turn stats for the chat screen's `↑input ↓output · CHn%` line -- the same shape
/// `ContextMeter` renders on Android from `GenerationStats`, just read off `OWGenerationStats`
/// instead of the JNI `LongArray`.
struct TurnStats {
    let promptTokens: Int32
    let cachedTokens: Int32
    let generatedTokens: Int32
    let prefillMillis: Int64
    let decodeMillis: Int64

    var totalPromptTokens: Int32 { promptTokens + cachedTokens }
    var cacheHitRate: Double {
        totalPromptTokens == 0 ? 0 : Double(cachedTokens) / Double(totalPromptTokens)
    }

    init(_ stats: OWGenerationStats) {
        promptTokens = stats.promptTokens
        cachedTokens = stats.cachedTokens
        generatedTokens = stats.generatedTokens
        prefillMillis = stats.prefillMillis
        decodeMillis = stats.decodeMillis
    }
}

enum EngineError: Error {
    case notLoaded
}

/// Async/await wrapper over `OWEngineSession`, the block-based Obj-C++ bridge to
/// `openweights::Session`. `OWEngineSession` itself is synchronous and blocks the calling
/// thread for the duration of load/generate, same contract as the Android JNI bridge -- this
/// wrapper is what hops that work off the main thread and turns the token callback into an
/// `AsyncThrowingStream`, mirroring what `LlamaCppEngine.kt` does with Kotlin coroutines.
@MainActor
final class EngineClient: ObservableObject {
    @Published private(set) var isLoaded = false
    @Published private(set) var isLoadingModel = false
    @Published private(set) var isGenerating = false
    @Published private(set) var lastStats: TurnStats?
    @Published private(set) var loadError: String?

    private var session: OWEngineSession?

    func loadModel(atPath path: String, contextSize: Int32 = 4096) async {
        isLoadingModel = true
        loadError = nil
        defer { isLoadingModel = false }

        let result: Result<OWEngineSession, Error> = await Task.detached(priority: .userInitiated) {
            do {
                let session = try OWEngineSession(modelPath: path, contextSize: contextSize)
                return .success(session)
            } catch {
                return .failure(error)
            }
        }.value

        switch result {
        case .success(let session):
            self.session = session
            isLoaded = true
        case .failure(let error):
            loadError = error.localizedDescription
            isLoaded = false
        }
    }

    /// Streams one turn's reply piece by piece over the *full* conversation so far --
    /// `Session::generate` re-renders the whole transcript every call and relies on its own
    /// prefix match against the KV cache to skip re-decoding what it already has, so passing
    /// only the newest message here would silently drop everything before it. Cancelling the
    /// returned stream's task calls `OWEngineSession.cancel`, same as pulling the stop button
    /// mid-generation on Android.
    func generate(history: [PersistedTurn], sampler: SamplerSettings) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream<String, Error>(bufferingPolicy: .unbounded) { continuation in
            guard let session else {
                continuation.finish(throwing: EngineError.notLoaded)
                return
            }

            let messages = history.map { OWChatMessage(role: $0.role, content: $0.text) }

            isGenerating = true
            let task = Task.detached(priority: .userInitiated) { [weak self] in
                var thrown: Error?
                do {
                    _ = try session.generate(
                        with: messages,
                        temperature: Float(sampler.temperature),
                        topP: Float(sampler.topP),
                        topK: Int32(sampler.topK),
                        maxTokens: Int32(sampler.maxTokens),
                        onToken: { piece in
                            continuation.yield(piece)
                        }
                    )
                } catch {
                    thrown = error
                }

                await MainActor.run {
                    self?.isGenerating = false
                    if let stats = session.lastStats {
                        self?.lastStats = TurnStats(stats)
                    }
                }

                if let thrown {
                    continuation.finish(throwing: thrown)
                } else {
                    continuation.finish()
                }
            }

            continuation.onTermination = { _ in
                session.cancel()
                task.cancel()
            }
        }
    }

    func resetConversation() {
        session?.reset()
        lastStats = nil
    }
}
