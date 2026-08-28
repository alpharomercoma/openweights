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

/// Mirrors `HubModel` from `core/hub/HuggingFaceClient.kt` -- Android's Discover screen has
/// no static catalog either, it searches the live Hub, scoped the same way this does.
struct HubModel: Identifiable, Decodable {
    let id: String
    let downloads: Int?
    let likes: Int?
    let tags: [String]?

    private enum CodingKeys: String, CodingKey {
        case id, downloads, likes, tags
    }

    var owner: String { String(id.split(separator: "/", maxSplits: 1).first ?? "") }
    var name: String {
        let parts = id.split(separator: "/", maxSplits: 1)
        return parts.count > 1 ? String(parts[1]) : id
    }

    /// A number followed by B or M, on a word boundary, e.g. "2.6B" in "LFM2.5-2.6B-GGUF".
    /// Read from the name for the same reason `HubModel.kt` does: the Hub only returns a
    /// true parameter count through an expensive `expand[]=gguf`, and every quantizer puts
    /// the size in the name anyway.
    var parameterHint: String? {
        guard let regex = try? NSRegularExpression(pattern: #"(?<![A-Za-z0-9.])\d+(\.\d+)?[BM](?![A-Za-z0-9])"#, options: .caseInsensitive) else {
            return nil
        }
        let range = NSRange(name.startIndex..., in: name)
        guard let match = regex.firstMatch(in: name, range: range), let matchRange = Range(match.range, in: name) else {
            return nil
        }
        return String(name[matchRange]).uppercased()
    }
}

/// One file inside a repository's tree, filtered down to `.gguf` before this app ever sees
/// it. Mirrors `HubFile` -- quantization is read from the filename for the same reason: GGUF
/// stores it in `general.file_type`, but that key sits after the tokenizer in the file, and
/// every quantizer puts it in the name regardless.
struct HubFile: Identifiable {
    let path: String
    let sizeBytes: Int64

    var id: String { path }
    var fileName: String { String(path.split(separator: "/").last ?? Substring(path)) }
    var quantizationLabel: String {
        let base = fileName.hasSuffix(".gguf") ? String(fileName.dropLast(5)) : fileName
        return String(base.split(separator: "-").last ?? Substring(base))
    }
    var isProjector: Bool { fileName.lowercased().hasPrefix("mmproj-") }
}

private struct SiblingPayload: Decodable {
    let rfilename: String
    let size: Int64?
    let lfs: LFSPayload?
}

private struct LFSPayload: Decodable {
    let size: Int64?
}

private struct ModelDetailPayload: Decodable {
    let siblings: [SiblingPayload]?
}

enum HubError: Error, LocalizedError {
    case badResponse
    case httpStatus(Int)

    var errorDescription: String? {
        switch self {
        case .badResponse: return "The Hugging Face Hub returned an unreadable response."
        case .httpStatus(let code): return "The Hugging Face Hub returned HTTP \(code)."
        }
    }
}

/// A thin client over the public Hugging Face Hub API, scoped to `apps=llama.cpp` the same
/// way `HuggingFaceClient.kt`'s `search` is -- that filter is the Hub's own "can llama.cpp
/// load this" computation, which differs from the `gguf` tag in about one repository in six
/// (video-diffusion weights and control vectors packaged as GGUF carry the tag but are not
/// chat models). No API key is required for public search or download.
actor HuggingFaceHub {
    private let session: URLSession
    private let baseURL = URL(string: "https://huggingface.co/api")!

    init(session: URLSession = .shared) {
        self.session = session
    }

    func search(query: String, limit: Int = 30) async throws -> [HubModel] {
        var components = URLComponents(url: baseURL.appendingPathComponent("models"), resolvingAgainstBaseURL: false)!
        var items = [
            URLQueryItem(name: "apps", value: "llama.cpp"),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "sort", value: "downloads"),
            URLQueryItem(name: "direction", value: "-1"),
        ]
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            items.append(URLQueryItem(name: "search", value: trimmed))
        }
        components.queryItems = items

        let (data, response) = try await session.data(from: components.url!)
        try Self.checkStatus(response)
        return try JSONDecoder().decode([HubModel].self, from: data)
    }

    /// GGUF files in a repository, sorted smallest first -- same ordering `HubModelDetail`
    /// uses, so the smallest (usually most heavily quantized, least capable but always
    /// loadable) variant is what a size-conscious picker would default to.
    func files(inRepository repoId: String) async throws -> [HubFile] {
        // `blobs=true` is what actually puts `size`/`lfs.size` on each sibling -- without it
        // the Hub returns filenames only, which is what silently produced sizeBytes == 0
        // before this was added (found live: a real download still completed, but with no
        // real total to report progress against or verify a resumed download's length).
        var components = URLComponents(url: baseURL.appendingPathComponent("models/\(repoId)"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "blobs", value: "true")]
        let (data, response) = try await session.data(from: components.url!)
        try Self.checkStatus(response)
        let payload = try JSONDecoder().decode(ModelDetailPayload.self, from: data)
        return (payload.siblings ?? [])
            .filter { $0.rfilename.lowercased().hasSuffix(".gguf") }
            .map { HubFile(path: $0.rfilename, sizeBytes: $0.lfs?.size ?? $0.size ?? 0) }
            .sorted { $0.sizeBytes < $1.sizeBytes }
    }

    func downloadURL(repoId: String, path: String) -> URL {
        baseURL.deletingLastPathComponent() // strip "/api"
            .appendingPathComponent(repoId)
            .appendingPathComponent("resolve/main")
            .appendingPathComponent(path)
    }

    private static func checkStatus(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw HubError.badResponse }
        guard (200..<300).contains(http.statusCode) else { throw HubError.httpStatus(http.statusCode) }
    }
}
