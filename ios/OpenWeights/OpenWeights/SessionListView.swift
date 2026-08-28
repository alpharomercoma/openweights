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

private let dateFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter
}()

/// The app's root screen: past conversations, persisted as JSON (`SessionStore`), plus
/// "New Chat" which routes through `ModelPickerView` before a `ChatView` is ever pushed --
/// there is no such thing as a session without a model attached.
struct SessionListView: View {
    @EnvironmentObject private var sessionStore: SessionStore
    @State private var isModelPickerPresented = false
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if sessionStore.sessions.isEmpty {
                    ContentUnavailableFallback()
                }
                ForEach(sessionStore.sessions) { session in
                    NavigationLink(value: session.id) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(session.title).font(.headline)
                            Text("\((session.modelPath as NSString).lastPathComponent) · \(dateFormatter.localizedString(for: session.updatedAt, relativeTo: Date()))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { offsets in
                    for index in offsets { sessionStore.delete(sessionStore.sessions[index]) }
                }
            }
            .navigationTitle("OpenWeights")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isModelPickerPresented = true
                    } label: {
                        Label("New Chat", systemImage: "square.and.pencil")
                    }
                }
            }
            .navigationDestination(for: UUID.self) { sessionID in
                ChatView(sessionID: sessionID)
            }
            .onAppear { sessionStore.reload() }
        }
        .sheet(isPresented: $isModelPickerPresented) {
            ModelPickerView { model in
                let session = ChatSession(title: "New chat", modelPath: model.path)
                sessionStore.save(session)
                path.append(session.id)
            }
        }
    }
}

private struct ContentUnavailableFallback: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("No chats yet").font(.headline)
            Text("Tap the compose button to import a model and start one.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}
