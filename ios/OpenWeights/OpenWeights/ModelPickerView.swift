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

private func formattedSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

/// Picks a model to start a new chat with, from whatever GGUF files have already been
/// imported, plus "Import" for a local file and "Discover" to search and download one from
/// the Hugging Face Hub (`DiscoverView`). No fit estimate here yet (how much RAM a quant
/// needs, whether it fits this device) -- that is Android's `FitEstimator`/fit-card, separate
/// follow-on work.
struct ModelPickerView: View {
    @EnvironmentObject private var modelLibrary: ModelLibrary
    @Environment(\.dismiss) private var dismiss
    @State private var isImporterPresented = false
    @State private var isDiscoverPresented = false
    @State private var importError: String?

    let onPick: (LocalModel) -> Void

    var body: some View {
        NavigationStack {
            List {
                if modelLibrary.models.isEmpty {
                    Text("No models imported yet.")
                        .foregroundStyle(.secondary)
                }
                ForEach(modelLibrary.models) { model in
                    Button {
                        onPick(model)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading) {
                            Text(model.name)
                            Text(formattedSize(model.sizeBytes))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { offsets in
                    for index in offsets { modelLibrary.delete(modelLibrary.models[index]) }
                }
                if let importError {
                    Text(importError).foregroundStyle(.red).font(.caption)
                }
            }
            .navigationTitle("Choose a model")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { isDiscoverPresented = true } label: {
                            Label("Discover on Hugging Face", systemImage: "magnifyingglass")
                        }
                        Button { isImporterPresented = true } label: {
                            Label("Import a local file", systemImage: "folder")
                        }
                    } label: {
                        Label("Add model", systemImage: "plus")
                    }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .fileImporter(
            isPresented: $isImporterPresented,
            allowedContentTypes: [UTType(filenameExtension: "gguf") ?? .data],
            onCompletion: handleImport
        )
        .sheet(isPresented: $isDiscoverPresented) {
            DiscoverView { model in
                onPick(model)
                dismiss()
            }
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        guard case .success(let sourceURL) = result else { return }
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessed { sourceURL.stopAccessingSecurityScopedResource() } }
        do {
            let model = try modelLibrary.importModel(from: sourceURL)
            onPick(model)
            dismiss()
        } catch {
            importError = error.localizedDescription
        }
    }
}
