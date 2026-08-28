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

struct LocalModel: Identifiable, Equatable {
    var id: String { path }
    let path: String
    let name: String
    let sizeBytes: Int64
}

/// The GGUF files a user has imported via the file picker, tracked by presence in
/// Documents/Models/ rather than any Room-style database -- there is no per-model metadata
/// on iOS yet (no FitEstimator, no fit-card), just enough to let the model picker and a
/// session's `modelPath` refer to the same set of files.
@MainActor
final class ModelLibrary: ObservableObject {
    @Published private(set) var models: [LocalModel] = []

    private let directory: URL

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        directory = documents.appendingPathComponent("Models", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        reload()
    }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey]
        )) ?? []
        models = files
            .filter { $0.pathExtension.lowercased() == "gguf" }
            .map { url in
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
                return LocalModel(path: url.path, name: url.lastPathComponent, sizeBytes: Int64(size))
            }
            .sorted { $0.name < $1.name }
    }

    /// Copies an externally-picked file into Documents/Models/ so it survives independent of
    /// wherever the user originally picked it from (Files app, iCloud Drive, AirDrop, ...).
    func importModel(from sourceURL: URL) throws -> LocalModel {
        let destination = directory.appendingPathComponent(sourceURL.lastPathComponent)
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.copyItem(at: sourceURL, to: destination)
        reload()
        guard let model = models.first(where: { $0.path == destination.path }) else {
            let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            return LocalModel(path: destination.path, name: destination.lastPathComponent, sizeBytes: Int64(size))
        }
        return model
    }

    func delete(_ model: LocalModel) {
        try? FileManager.default.removeItem(atPath: model.path)
        reload()
    }
}
