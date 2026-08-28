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

private func formattedSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

/// Milestone 6: search the live Hugging Face Hub instead of only importing a file the user
/// already has somewhere else. Scoped to `apps=llama.cpp` results the same way Android's
/// Discover screen is -- no static catalog on either platform, both search the Hub directly.
/// No FitEstimator/fit-card equivalent here yet (how much RAM a quant needs, whether it fits
/// this device): that is Android-only device-capability plumbing, separate follow-on work.
struct DiscoverView: View {
    @EnvironmentObject private var modelLibrary: ModelLibrary
    @Environment(\.dismiss) private var dismiss
    let onDownloaded: (LocalModel) -> Void

    @State private var query = ""
    @State private var results: [HubModel] = []
    @State private var isSearching = false
    @State private var searchError: String?
    private let hub = HuggingFaceHub()

    var body: some View {
        NavigationStack {
            List {
                if let searchError {
                    Text(searchError).foregroundStyle(.red).font(.caption)
                }
                ForEach(results) { model in
                    NavigationLink {
                        RepositoryFilesView(model: model, hub: hub, onDownloaded: { downloaded in
                            onDownloaded(downloaded)
                            dismiss()
                        })
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.id).font(.headline)
                            HStack(spacing: 8) {
                                if let hint = model.parameterHint {
                                    Text(hint)
                                }
                                if let downloads = model.downloads {
                                    Text("\(downloads) downloads")
                                }
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search Hugging Face models")
            .onSubmit(of: .search) { Task { await runSearch() } }
            .onChange(of: query) { _ in Task { await runSearch() } }
            .overlay {
                if isSearching && results.isEmpty {
                    ProgressView()
                } else if !isSearching && results.isEmpty && !query.isEmpty {
                    ContentUnavailableFallback(text: "No models found for \"\(query)\".")
                }
            }
            .navigationTitle("Discover")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task { await runSearch() }
        }
    }

    private func runSearch() async {
        isSearching = true
        searchError = nil
        do {
            results = try await hub.search(query: query)
        } catch {
            searchError = error.localizedDescription
        }
        isSearching = false
    }
}

/// GGUF quant variants inside one repository -- picking one starts the download immediately;
/// there is no separate "confirm" step, matching how quickly Android's own row-tap-to-download
/// flow commits.
private struct RepositoryFilesView: View {
    let model: HubModel
    let hub: HuggingFaceHub
    let onDownloaded: (LocalModel) -> Void

    @EnvironmentObject private var modelLibrary: ModelLibrary
    @State private var files: [HubFile] = []
    @State private var isLoading = true
    @State private var loadError: String?
    @State private var downloadingFile: HubFile?
    @State private var progress: DownloadProgress?
    private let downloader = ModelDownloader()

    var body: some View {
        List {
            if let loadError {
                Text(loadError).foregroundStyle(.red)
            }
            ForEach(files) { file in
                Button {
                    Task { await startDownload(file) }
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(file.quantizationLabel).font(.headline)
                            Text(formattedSize(file.sizeBytes)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if downloadingFile?.id == file.id, let progress {
                            downloadIndicator(progress)
                        }
                    }
                }
                .disabled(file.isProjector || downloadingFile != nil)
            }
        }
        .overlay {
            if isLoading { ProgressView() }
        }
        .navigationTitle(model.name)
        .task {
            do {
                files = try await hub.files(inRepository: model.id)
            } catch {
                loadError = error.localizedDescription
            }
            isLoading = false
        }
    }

    @ViewBuilder
    private func downloadIndicator(_ progress: DownloadProgress) -> some View {
        switch progress {
        case .progress(let downloaded, let total):
            ProgressView(value: total > 0 ? Double(downloaded) / Double(total) : 0)
                .frame(width: 60)
        case .finished:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
        }
    }

    private func startDownload(_ file: HubFile) async {
        downloadingFile = file
        loadError = nil
        let url = await hub.downloadURL(repoId: model.id, path: file.path)
        let destination = modelLibrary.directoryURL.appendingPathComponent(file.fileName)

        for await update in await downloader.download(from: url, to: destination, totalBytes: file.sizeBytes) {
            progress = update
            switch update {
            case .progress:
                continue
            case .finished(let finishedURL):
                modelLibrary.reload()
                let size = (try? finishedURL.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? Int(file.sizeBytes)
                onDownloaded(LocalModel(path: finishedURL.path, name: finishedURL.lastPathComponent, sizeBytes: Int64(size)))
            case .failed(let error):
                loadError = error.localizedDescription
                downloadingFile = nil
            }
        }
    }
}

private struct ContentUnavailableFallback: View {
    let text: String
    var body: some View {
        Text(text).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
    }
}
