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

enum DownloadProgress {
    case progress(bytesDownloaded: Int64, totalBytes: Int64)
    case finished(URL)
    case failed(Error)
}

enum DownloadError: Error, LocalizedError {
    case httpStatus(Int)
    case noContentLength

    var errorDescription: String? {
        switch self {
        case .httpStatus(let code): return "Download failed with HTTP \(code)."
        case .noContentLength: return "The server did not report a file size."
        }
    }
}

/// Mirrors `ModelDownloader.kt`'s resume-rather-than-restart contract: partial bytes go to a
/// `.part` file beside the destination, a `Range: bytes=N-` header picks up where a previous
/// attempt (or a previous app launch) left off, and only a completed download gets renamed
/// into the real destination name -- so a half-downloaded file is never mistaken for a
/// finished one, on either platform.
actor ModelDownloader: NSObject {
    private var session: URLSession!
    private var continuation: AsyncStream<DownloadProgress>.Continuation?
    private var destination: URL?
    private var partial: URL?
    private var expectedTotal: Int64 = 0
    private var alreadyHave: Int64 = 0
    private var outputHandle: FileHandle?

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: DownloaderDelegate(owner: self), delegateQueue: nil)
    }

    func download(from url: URL, to destination: URL, totalBytes: Int64) -> AsyncStream<DownloadProgress> {
        AsyncStream { continuation in
            Task {
                await self.start(url: url, destination: destination, totalBytes: totalBytes, continuation: continuation)
            }
        }
    }

    private func start(url: URL, destination: URL, totalBytes: Int64, continuation: AsyncStream<DownloadProgress>.Continuation) {
        self.continuation = continuation
        self.destination = destination
        self.expectedTotal = totalBytes

        let fm = FileManager.default
        try? fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)

        if fm.fileExists(atPath: destination.path),
           let attrs = try? fm.attributesOfItem(atPath: destination.path),
           let size = attrs[.size] as? Int64, size == totalBytes {
            // Already fully downloaded under this name.
            continuation.yield(.finished(destination))
            continuation.finish()
            return
        }

        let partial = destination.deletingLastPathComponent().appendingPathComponent(destination.lastPathComponent + ".part")
        self.partial = partial

        var startByte: Int64 = 0
        if let attrs = try? fm.attributesOfItem(atPath: partial.path), let size = attrs[.size] as? Int64, size <= totalBytes {
            startByte = size
        } else {
            try? fm.removeItem(at: partial)
            fm.createFile(atPath: partial.path, contents: nil)
        }
        alreadyHave = startByte

        outputHandle = FileHandle(forWritingAtPath: partial.path)
        outputHandle?.seekToEndOfFile()

        var request = URLRequest(url: url)
        if startByte > 0 {
            request.setValue("bytes=\(startByte)-", forHTTPHeaderField: "Range")
        }
        let task = session.dataTask(with: request)
        task.resume()
    }

    fileprivate func handleResponse(_ response: URLResponse) -> Bool {
        guard let http = response as? HTTPURLResponse else { return false }
        guard http.statusCode == 200 || http.statusCode == 206 else {
            continuation?.yield(.failed(DownloadError.httpStatus(http.statusCode)))
            continuation?.finish()
            return false
        }
        return true
    }

    fileprivate func handleData(_ data: Data) {
        outputHandle?.write(data)
        alreadyHave += Int64(data.count)
        continuation?.yield(.progress(bytesDownloaded: alreadyHave, totalBytes: expectedTotal))
    }

    fileprivate func handleCompletion(error: Error?) {
        outputHandle?.closeFile()
        outputHandle = nil

        if let error {
            continuation?.yield(.failed(error))
            continuation?.finish()
            return
        }

        guard let destination, let partial else {
            continuation?.finish()
            return
        }

        let fm = FileManager.default
        try? fm.removeItem(at: destination)
        do {
            try fm.moveItem(at: partial, to: destination)
            continuation?.yield(.finished(destination))
        } catch {
            continuation?.yield(.failed(error))
        }
        continuation?.finish()
    }
}

/// `URLSessionDataDelegate` needs a plain `NSObject`-conforming target; the actor above holds
/// the real state, this just marshals delegate callbacks onto it.
private final class DownloaderDelegate: NSObject, URLSessionDataDelegate {
    weak var owner: ModelDownloader?

    init(owner: ModelDownloader) {
        self.owner = owner
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        Task { [owner] in
            let ok = await owner?.handleResponse(response) ?? false
            completionHandler(ok ? .allow : .cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        Task { [owner] in await owner?.handleData(data) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        Task { [owner] in await owner?.handleCompletion(error: error) }
    }
}
