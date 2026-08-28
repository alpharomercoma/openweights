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

/// Sampler knobs a chat screen exposes, mirroring `SamplerConfig`'s fields on the C++ side
/// (`engine_session.h`). `maxTokens == 0` means "until end-of-turn or the context fills."
struct SamplerSettings: Codable, Equatable, Hashable {
    var temperature: Double = 0.8
    var topP: Double = 0.95
    var topK: Int = 40
    var maxTokens: Int = 512
}

struct PersistedTurn: Codable, Identifiable, Equatable, Hashable {
    let id: UUID
    var role: String
    var text: String

    init(id: UUID = UUID(), role: String, text: String) {
        self.id = id
        self.role = role
        self.text = text
    }
}

/// One conversation, persisted as its own JSON file. Reopening a session re-sends its full
/// `turns` history to `Session::generate` on the first turn after reload -- the KV cache
/// itself never survives a process restart, on iOS any more than on Android, so that first
/// turn is expected to show `cachedTokens == 0` even though the transcript looks unbroken.
struct ChatSession: Codable, Identifiable, Equatable, Hashable {
    let id: UUID
    var title: String
    var modelPath: String
    var sampler: SamplerSettings
    var turns: [PersistedTurn]
    var createdAt: Date
    var updatedAt: Date

    init(
        id: UUID = UUID(),
        title: String,
        modelPath: String,
        sampler: SamplerSettings = SamplerSettings(),
        turns: [PersistedTurn] = [],
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.modelPath = modelPath
        self.sampler = sampler
        self.turns = turns
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// JSON-file persistence, one file per session, under Documents/Sessions/. Deliberately not
/// SwiftData or Core Data: this app's deployment target is iOS 16.4 for Simulator/toolchain
/// parity with the rest of Milestone 2, and SwiftData needs iOS 17. A directory of small JSON
/// files is the same shape Room's `usage_ledger` table serves on Android, just without a SQL
/// engine backing it -- swapping to SwiftData later is a storage-layer change only, not a
/// model change, since every call already goes through this one type.
@MainActor
final class SessionStore: ObservableObject {
    @Published private(set) var sessions: [ChatSession] = []

    private let directory: URL

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        directory = documents.appendingPathComponent("Sessions", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        reload()
    }

    private func fileURL(for session: ChatSession) -> URL {
        directory.appendingPathComponent("\(session.id.uuidString).json")
    }

    func reload() {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        let loaded = files
            .filter { $0.pathExtension == "json" }
            .compactMap { url -> ChatSession? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? decoder.decode(ChatSession.self, from: data)
            }
        sessions = loaded.sorted { $0.updatedAt > $1.updatedAt }
    }

    func save(_ session: ChatSession) {
        var updated = session
        updated.updatedAt = Date()
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(updated) else { return }
        try? data.write(to: fileURL(for: updated), options: .atomic)

        if let index = sessions.firstIndex(where: { $0.id == updated.id }) {
            sessions[index] = updated
        } else {
            sessions.insert(updated, at: 0)
        }
        sessions.sort { $0.updatedAt > $1.updatedAt }
    }

    func delete(_ session: ChatSession) {
        try? FileManager.default.removeItem(at: fileURL(for: session))
        sessions.removeAll { $0.id == session.id }
    }
}
