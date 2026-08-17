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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.BuildConfig
import io.github.alpharomercoma.openweights.core.data.ThemeChoice
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.component.Mark
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
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
    /**
     * Pops back to the conversation, when this screen was pushed from it.
     *
     * Nullable so the arrow only appears where there is somewhere to go back to, which is
     * also what keeps every existing caller and every screen test compiling while the
     * navigation is being rebuilt around it.
     */
    onBack: (() -> Unit)? = null,
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
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
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
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
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
 * they want to know what they installed. A lockup rather than four paragraphs: the mark,
 * the name, the one sentence that is the whole point, and the two facts that make that
 * sentence checkable.
 *
 * The network disclosure that used to live here in full was three paragraphs restating
 * Tools, screen by screen and switch by switch, in a place where none of it could be acted
 * on. One line and a pointer is more honest about where the answer lives, and the tools
 * that reach the network now say so under their own heading.
 */
@Composable
private fun AboutSection() {
    val links = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mark(size = 44.dp)
        Text("OpenWeights", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Open weight models, running on this phone. No account, no server of " +
                "ours, no telemetry.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Searching the web and downloading a model are the parts that reach " +
                "the network. Both are yours to switch off in Tools.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Metric("${BuildConfig.VERSION_NAME} · Apache License 2.0")
        TextButton(onClick = { links.openUri(SOURCE_URL) }) { Text("View the source") }
    }
}

/** The claim above it is only worth making if this is one tap away. */
private const val SOURCE_URL = "https://github.com/alpharomercoma/openweights"

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
                        activeContainerColor = OpenWeightsColors.Lime,
                        activeContentColor = OpenWeightsColors.Ink,
                        activeBorderColor = OpenWeightsColors.Lime,
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
            text = "Only for gated or private models. Encrypted with a key held in this " +
                "phone's hardware, and sent to huggingface.co and nowhere else.",
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
            AccentButton(
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
                // The name, then what it is. It used to print the description first and the
                // kind under it, so a phone reported "8 cores, arm64" with "Processor"
                // beneath, which is a label sitting under its own value.
                Text(device.kind.label, style = MaterialTheme.typography.bodyMedium)
                val detail = buildString {
                    // llama.cpp names the CPU device "CPU", so the second line read
                    // "Processor" over "CPU", which is a word and then the same word.
                    if (!device.description.equals(device.kind.name, ignoreCase = true)) {
                        append(device.description)
                    }
                    // A GPU's memory is its own number and worth saying. A CPU
                    // backend's is the phone's RAM, which "This device" prints
                    // three rows below, so it was the same figure twice.
                    if (device.totalMemoryBytes > 0 && device.supportsOffload) {
                        if (isNotEmpty()) append(" · ")
                        append(formatBytes(device.totalMemoryBytes))
                    }
                }
                if (detail.isNotEmpty()) Metric(detail)
            }
        }

        if (state.computeDevices.none { it.kind != ComputeDeviceKind.CPU }) {
            // Being explicit beats a greyed-out toggle nobody can explain.
            Text(
                text = "This build runs on the CPU. On the phones we have measured, that " +
                    "is the faster path anyway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EngineFeatures(state.engineInfo)
    }
}

/**
 * What the engine was built with, as facts rather than as its own debug line.
 *
 * llama.cpp returns one string of `NAME = 1 | NAME = 0 | ... | backends: CPU OpenCL`, and
 * printing it whole put a pipe-delimited dump in the middle of a settings screen: the
 * screen's worst paragraph by a distance, and the kind of thing "technical and brutal"
 * describes exactly. The information is worth keeping, because which kernels a build got
 * is the difference between eight tokens a second and fourteen, so it is kept and set out
 * rather than thrown away.
 *
 * Only the features that are on. A flag at zero is the absence of a thing, and a list of
 * absences is not a fact about this phone worth six lines.
 */
@Composable
private fun EngineFeatures(info: String) {
    if (info.isBlank()) return

    val parts = info.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val backends = parts.firstOrNull { it.startsWith("backends:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    val features = parts
        .filter { it.endsWith("= 1") }
        .map { it.substringBefore('=').trim().removePrefix("CPU :").trim() }
        .filter { it.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (backends.isNotEmpty()) Metric("backends ${backends.joinToString(", ")}")
        if (features.isNotEmpty()) Metric(features.joinToString(" · "))
    }
}

/**
 * The enum, in words.
 *
 * It used to print `name.lowercase()`, so a phone with an integrated GPU reported
 * "integrated gpu" in a line of otherwise ordinary prose, which is a constant leaking into
 * the interface rather than a description of anything.
 */
private val ComputeDeviceKind.label: String
    get() = when (this) {
        ComputeDeviceKind.CPU -> "Processor"
        ComputeDeviceKind.GPU -> "Graphics"
        ComputeDeviceKind.INTEGRATED_GPU -> "Built in graphics"
        ComputeDeviceKind.ACCELERATOR -> "Accelerator"
        ComputeDeviceKind.OTHER -> "Other"
    }

@Composable
private fun DeviceSection(state: SettingsUiState) {
    val device = state.device ?: return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("This device", style = MaterialTheme.typography.titleSmall)
        Metric("${device.socModel} · ${device.cpuCores} cores")
        // Short enough not to wrap, because a line of figures that breaks after "usable by"
        // and orphans "a model" on its own line is a line nobody finishes reading.
        Metric(
            "${formatBytes(device.totalMemoryBytes)} RAM · " +
                "${formatBytes(device.usableMemoryBytes)} usable",
        )
        Metric("${formatBytes(device.freeStorageBytes)} free")
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
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
