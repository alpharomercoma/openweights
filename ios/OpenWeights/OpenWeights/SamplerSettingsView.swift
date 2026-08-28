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

/// Exposes the same knobs `SamplerConfig` (`engine_session.h`) takes: temperature, top-p,
/// top-k, and a token budget. Repeat-penalty/min-p/seed stay at their C++ defaults for now --
/// nothing in this milestone's UI needs them yet.
struct SamplerSettingsView: View {
    @Binding var settings: SamplerSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Sampling") {
                    LabeledContent("Temperature", value: settings.temperature, format: .number.precision(.fractionLength(2)))
                    Slider(value: $settings.temperature, in: 0...2, step: 0.05)

                    LabeledContent("Top-P", value: settings.topP, format: .number.precision(.fractionLength(2)))
                    Slider(value: $settings.topP, in: 0...1, step: 0.01)

                    Stepper("Top-K: \(settings.topK)", value: $settings.topK, in: 1...200)
                }
                Section("Output") {
                    Stepper("Max tokens: \(settings.maxTokens)", value: $settings.maxTokens, in: 16...4096, step: 16)
                }
                Section {
                    Button("Reset to defaults") { settings = SamplerSettings() }
                }
            }
            .navigationTitle("Sampler")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
