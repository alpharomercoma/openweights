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

package io.github.alpharomercoma.openweights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.BuildConfig
import io.github.alpharomercoma.openweights.core.data.ThemeChoice
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.engine.ComputeDeviceKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSelectTheme: (ThemeChoice) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either, doing both is what left the
        // chrome floating away from the edges it belongs to.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppearanceSection(selected = state.theme, onSelect = onSelectTheme)

            HorizontalDivider()
            TokenSection(state = state, onSave = onSaveToken, onClear = onClearToken)

            HorizontalDivider()
            ComputeSection(state)

            HorizontalDivider()
            DeviceSection(state)

            HorizontalDivider()
            AboutSection()
        }
    }
}

/**
 * What this app is, at the bottom of the last screen.
 *
 * Last because it is the least urgent thing here and the first thing someone looks for when
 * they want to know what they installed. It says the two facts that are the whole point:
 * the models are open weights that the user chose and downloaded, and nothing they type is
 * sent anywhere. An app making that second claim should be checkable, so the licence and
 * the source are named rather than implied.
 */
@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("About", style = MaterialTheme.typography.titleSmall)
        Text(
            "OpenWeights runs open weight language models on this phone. Models come from " +
                "Hugging Face, you choose which ones, and they run on the processor in " +
                "your hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Nothing you type leaves the device. There is no account, no server, and no " +
                "telemetry. The only requests it makes are the ones you ask for: finding " +
                "and downloading a model, and any web search you switch on in Tools.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Metric("version ${BuildConfig.VERSION_NAME}")
        Metric("Apache License 2.0")
        Metric("github.com/alpharomercoma/openweights")
    }
}

/**
 * Light, dark, or whatever the phone is doing.
 *
 * A segmented control rather than a switch, because "follow the system" is a real third
 * answer and the one most people want. A two-state toggle would have to drop it or hide
 * it behind a long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(selected: ThemeChoice, onSelect: (ThemeChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeChoice.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        activeBorderColor = MaterialTheme.colorScheme.primary,
                        inactiveBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    label = { Text(choice.label, maxLines = 1) },
                )
            }
        }
    }
}

/** Sentence case, because these are choices in a sentence rather than constants. */
private val ThemeChoice.label: String
    get() = when (this) {
        ThemeChoice.SYSTEM -> "System"
        ThemeChoice.LIGHT -> "Light"
        ThemeChoice.DARK -> "Dark"
    }

@Composable
private fun TokenSection(state: SettingsUiState, onSave: (String) -> Unit, onClear: () -> Unit) {
    var draft by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hugging Face access token", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Only needed for gated or private models. It is encrypted with a key " +
                "held in this device's hardware keystore, and is sent to huggingface.co " +
                "and nowhere else.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (state.hasToken) "Replace saved token" else "hf_…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(Radius.sm),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank() && !state.isCheckingToken,
            ) {
                Text(if (state.isCheckingToken) "Checking…" else "Save and verify")
            }
            if (state.hasToken) {
                TextButton(
                    onClick = onClear,
                    // The accent means "this is the thing to do". Deleting is not, so it
                    // takes the error colour: still reachable, never the default read.
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            }
        }

        state.tokenStatus?.let { status -> Metric(status) }
    }
}

@Composable
private fun ComputeSection(state: SettingsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Compute", style = MaterialTheme.typography.titleSmall)

        state.computeDevices.forEach { device ->
            Column {
                Text(device.description, style = MaterialTheme.typography.bodyMedium)
                Metric(
                    buildString {
                        append(device.kind.name.lowercase().replace('_', ' '))
                        if (device.totalMemoryBytes > 0) {
                            append(" · ")
                            append(formatBytes(device.totalMemoryBytes))
                        }
                    },
                )
            }
        }

        if (state.computeDevices.none { it.kind != ComputeDeviceKind.CPU }) {
            // Being explicit beats a greyed-out toggle nobody can explain.
            Text(
                text = "No GPU or NPU backend is available in this build. On phones we have " +
                    "measured, the tuned CPU path is faster than Vulkan anyway; NPU access " +
                    "would need a second inference engine and per-chip model conversions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.engineInfo.isNotEmpty()) {
            Metric(state.engineInfo)
        }
    }
}

@Composable
private fun DeviceSection(state: SettingsUiState) {
    val device = state.device ?: return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("This device", style = MaterialTheme.typography.titleSmall)
        Metric("${device.socModel} · ${device.cpuCores} cores")
        Metric(
            "memory ${formatBytes(device.totalMemoryBytes)} total · " +
                "${formatBytes(device.usableMemoryBytes)} usable by a model",
        )
        Metric("storage ${formatBytes(device.freeStorageBytes)} free")
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
@Composable
private fun SettingsScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(),
            onSelectTheme = {},
            onSaveToken = {},
            onClearToken = {},
        )
    }
}
