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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.engine.ComputeDeviceKind
import io.github.alpharomercoma.openweights.ui.discover.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
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
            TokenSection(state = state, onSave = onSaveToken, onClear = onClearToken)

            HorizontalDivider()
            ComputeSection(state)

            HorizontalDivider()
            DeviceSection(state)
        }
    }
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
            shape = RoundedCornerShape(12.dp),
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
                TextButton(onClick = onClear) { Text("Remove") }
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

@Preview(showBackground = true, backgroundColor = 0xFF0A0E11)
@Composable
private fun SettingsScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        SettingsScreen(state = SettingsUiState(), onSaveToken = {}, onClearToken = {})
    }
}
