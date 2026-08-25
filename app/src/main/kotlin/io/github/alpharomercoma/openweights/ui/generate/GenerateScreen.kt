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

package io.github.alpharomercoma.openweights.ui.generate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.generation.GenerationBundleSpec
import io.github.alpharomercoma.openweights.core.generation.ImageSize
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    state: GenerateUiState,
    onPromptChange: (String) -> Unit,
    onStepsChange: (Int) -> Unit,
    onSizeChange: (ImageSize) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onSelectBundle: (GenerationBundleSpec) -> Unit,
    onDismissError: () -> Unit,
    onBrowseModels: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            onDismissError()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.generate_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
        },
    ) { padding ->
        when {
            state.runtimeMissing -> RuntimeMissingPane(modifier = Modifier.padding(padding))
            state.availableBundles.isEmpty() -> NoBundlesPane(
                onBrowse = onBrowseModels,
                modifier = Modifier.padding(padding),
            )
            else -> GenerateContent(
                state = state,
                onPromptChange = onPromptChange,
                onStepsChange = onStepsChange,
                onSizeChange = onSizeChange,
                onGenerate = onGenerate,
                onCancel = onCancel,
                onSelectBundle = onSelectBundle,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun GenerateContent(
    state: GenerateUiState,
    onPromptChange: (String) -> Unit,
    onStepsChange: (Int) -> Unit,
    onSizeChange: (ImageSize) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onSelectBundle: (GenerationBundleSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Model picker — chips for each available bundle.
        if (state.availableBundles.size > 1) {
            BundlePicker(
                bundles = state.availableBundles,
                selected = state.selectedBundleSpec,
                onSelect = onSelectBundle,
            )
        }

        // Result image — shown above the controls so the user can see what they changed.
        ResultPane(
            result = state.lastResult,
            isGenerating = state.isGenerating,
            step = state.progressStep,
            totalSteps = state.steps,
        )

        // Prompt field.
        OutlinedTextField(
            value = state.prompt,
            onValueChange = onPromptChange,
            label = { Text(stringResource(R.string.generate_prompt_label)) },
            placeholder = { Text(stringResource(R.string.generate_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5,
            enabled = !state.isGenerating,
        )

        // Steps slider.
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Caption(stringResource(R.string.generate_steps_label))
                Caption("${state.steps}")
            }
            val cap = state.capability
            if (cap != null) {
                Slider(
                    value = state.steps.toFloat(),
                    onValueChange = { onStepsChange(it.roundToInt()) },
                    valueRange = cap.steps.first.toFloat()..cap.steps.last.toFloat(),
                    steps = (cap.steps.last - cap.steps.first - 1).coerceAtLeast(0),
                    enabled = !state.isGenerating,
                )
            }
        }

        // Size chips — only show options the model supports.
        val cap = state.capability
        if (cap != null && cap.sizes.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                cap.sizes.forEach { size ->
                    val selected = state.size == size
                    val label = "${size.width}×${size.height}"
                    FilledTonalButton(
                        onClick = { onSizeChange(size) },
                        enabled = !state.isGenerating,
                    ) {
                        Text(
                            label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // Generate / Cancel button.
        Button(
            onClick = if (state.isGenerating) onCancel else onGenerate,
            enabled = state.isGenerating || state.canGenerate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isGenerating) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Text(
                    stringResource(R.string.generate_stop),
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Text(
                    stringResource(R.string.generate_button),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // Stats line under the button.
        state.lastResult?.let { result ->
            Caption(
                text = buildString {
                    append("${result.totalMillis / 1000}s")
                    if (result.backend.isNotEmpty()) append(" · ${result.backend}")
                    append(" · seed ${result.seed}")
                },
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BundlePicker(
    bundles: List<GenerationBundleSpec>,
    selected: GenerationBundleSpec?,
    onSelect: (GenerationBundleSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bundles.forEach { spec ->
            val isSelected = spec.id == selected?.id
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(Radius.sm),
                    )
                    .clickable { onSelect(spec) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = spec.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultPane(
    result: GenerationResult?,
    isGenerating: Boolean,
    step: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (result != null) {
            AsyncImage(
                model = result.imagePath,
                contentDescription = result.prompt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }

        AnimatedVisibility(
            visible = isGenerating,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val progress = animateFloatAsState(
                targetValue = if (totalSteps > 0) step.toFloat() / totalSteps else 0f,
                label = "step_progress",
            )
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun NoBundlesPane(
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.generate_no_bundle_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.generate_no_bundle_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBrowse) {
            Text(stringResource(R.string.generate_browse_models))
        }
    }
}

@Composable
private fun RuntimeMissingPane(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.generate_no_runtime_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.generate_no_runtime_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
