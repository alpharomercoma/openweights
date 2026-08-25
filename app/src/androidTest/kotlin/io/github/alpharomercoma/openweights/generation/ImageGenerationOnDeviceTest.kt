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

package io.github.alpharomercoma.openweights.generation

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GenerationBundle
import io.github.alpharomercoma.openweights.core.generation.GenerationEvent
import io.github.alpharomercoma.openweights.core.generation.GenerationRuntime
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import io.github.alpharomercoma.openweights.core.generation.ImageRequest
import io.github.alpharomercoma.openweights.core.generation.ImageSize
import io.github.alpharomercoma.openweights.core.generation.mnn.MnnImageGenerator
import io.github.alpharomercoma.openweights.ui.chat.Fixtures
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether this phone can actually draw a picture, asked of the weights rather than the docs.
 */
@RunWith(AndroidJUnit4::class)
class ImageGenerationOnDeviceTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir: File
        get() = File(context.filesDir, "generated").apply { mkdirs() }

    private var generator: MnnImageGenerator? = null

    @After
    fun tearDown() {
        generator?.close()
        generator = null
    }

    @Test(timeout = 600_000)
    fun stableDiffusionLoadsOnTheGpuAndDrawsAPicture() = runBlocking {
        val dir = bundleDir(SD_DIR)
        requireBundle(dir, MnnImageGenerator.SD15_REQUIRED_FILES)

        val memory = memoryProbe()
        Log.i(TAG, "before load: $memory")
        val gen = MnnImageGenerator(outputDir).also { generator = it }
        gen.load(bundle(dir, mnnModelType = SD15))
        val capability = gen.capability
        Log.i(TAG, "loaded, capability=$capability, after load: $memory")
        assertThat(capability).isNotNull()
        assertThat(capability!!.sizes).contains(ImageSize(512, 512))

        val events = gen.generate(
            ImageRequest(prompt = PROMPT, size = ImageSize(512, 512), steps = 20, guidance = 7.5f, seed = 42L),
        ).toList()

        events.filterIsInstance<GenerationEvent.Progress>().forEach {
            Log.i(TAG, "step ${it.step}/${it.totalSteps}, $memory")
        }
        val completed = events.filterIsInstance<GenerationEvent.Completed<Artifact>>().singleOrNull()
        Log.i(TAG, "outcome events=$events")
        if (completed == null) throw AssertionError("no picture completed: $events")

        val picture = File(completed.output.path)
        Log.i(TAG, "drew ${picture.name}: ${picture.length()} bytes in ${completed.stats.totalMillis}ms " +
            "(${completed.stats.perStepMillis}ms/step, seed=${completed.stats.seed}, backend=${completed.stats.backend})")
        assertThat(completed.stats.backend).ignoringCase().contains("opencl")
        assertRealPicture(picture, 512, 512)
    }

    @Test(timeout = 600_000)
    fun aRunCanBeStoppedBetweenSteps() = runBlocking {
        val dir = bundleDir(SD_DIR)
        requireBundle(dir, MnnImageGenerator.SD15_REQUIRED_FILES)

        val gen = MnnImageGenerator(outputDir).also { generator = it }
        gen.load(bundle(dir, mnnModelType = SD15))

        var cancelled = false
        val events = mutableListOf<GenerationEvent<Artifact>>()
        gen.generate(
            ImageRequest(prompt = PROMPT, size = ImageSize(512, 512), steps = 50, guidance = 7.5f, seed = 7L),
        ).collect { event ->
            events += event
            if (!cancelled && event is GenerationEvent.Progress && event.step >= 2) {
                gen.cancel(); cancelled = true
            }
        }
        Log.i(TAG, "cancellation events=$events")
        assertThat(cancelled).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.Completed<Artifact>>()).isEmpty()
        assertThat(events.last()).isEqualTo(GenerationEvent.Cancelled)
    }

    @Test(timeout = 600_000)
    fun sanaEncodesWithItsLlmAndDrawsAPicture() = runBlocking {
        val dir = bundleDir(SANA_DIR)
        requireBundle(dir, MnnImageGenerator.SANA_REQUIRED_FILES)

        val memory = memoryProbe()
        val gen = MnnImageGenerator(outputDir).also { generator = it }
        gen.load(bundle(dir, mnnModelType = SANA))
        Log.i(TAG, "sana loaded, capability=${gen.capability}, $memory")
        assertThat(gen.capability).isNotNull()

        val events = gen.generate(
            ImageRequest(prompt = PROMPT, size = ImageSize(512, 512), steps = 10, guidance = 4.5f, seed = 42L),
        ).toList()

        val completed = events.filterIsInstance<GenerationEvent.Completed<Artifact>>().singleOrNull()
        Log.i(TAG, "sana outcome events=$events")
        if (completed == null) throw AssertionError("sana completed no picture: $events")

        val picture = File(completed.output.path)
        Log.i(TAG, "sana drew ${picture.name}: ${picture.length()} bytes in " +
            "${completed.stats.totalMillis}ms (backend=${completed.stats.backend})")
        assertRealPicture(picture, 512, 512)
    }

    @Test(timeout = 600_000)
    fun generateParisWoman() = runBlocking {
        val dir = bundleDir(SANA_DIR)
        requireBundle(dir, MnnImageGenerator.SANA_REQUIRED_FILES)

        val prompt = "A high-quality portrait of a beautiful woman in Paris, France, Eiffel tower in the background, golden hour lighting, 8k resolution, photorealistic"
        val gen = MnnImageGenerator(outputDir).also { generator = it }
        gen.load(bundle(dir, mnnModelType = SANA))
        val events = gen.generate(
            ImageRequest(prompt = prompt, size = ImageSize(512, 512), steps = 10, guidance = 4.5f, seed = 42L),
        ).toList()

        val completed = events.filterIsInstance<GenerationEvent.Completed<Artifact>>().singleOrNull()
        if (completed == null) throw AssertionError("sana completed no picture: $events")
        val picture = File(completed.output.path)
        Log.i(TAG, "paris woman picture on device: ${picture.absolutePath}")
        assertRealPicture(picture, 512, 512)
    }

    @Test(timeout = 600_000)
    fun generateParisWomanWithStableDiffusion() = runBlocking {
        val dir = bundleDir(SD_DIR)
        requireBundle(dir, MnnImageGenerator.SD15_REQUIRED_FILES)

        val prompt = "A high-quality portrait of a beautiful woman in Paris, France, Eiffel tower in the background, golden hour lighting, 8k resolution, photorealistic"
        val gen = MnnImageGenerator(outputDir).also { generator = it }
        gen.load(bundle(dir, mnnModelType = SD15))
        val events = gen.generate(
            ImageRequest(prompt = prompt, size = ImageSize(512, 512), steps = 20, guidance = 7.5f, seed = 42L),
        ).toList()

        val completed = events.filterIsInstance<GenerationEvent.Completed<Artifact>>().singleOrNull()
        if (completed == null) throw AssertionError("sd15 completed no picture: $events")
        val picture = File(completed.output.path)
        Log.i(TAG, "paris woman sd15 picture on device: ${picture.absolutePath}")
        assertRealPicture(picture, 512, 512)
    }

    private fun assertRealPicture(file: File, width: Int, height: Int) {
        assertThat(file.isFile).isTrue()
        assertThat(file.length()).isGreaterThan(5_000L)
        val bytes = file.readBytes()
        assertThat(bytes.copyOfRange(1, 4).map { it.toInt().toChar() }.joinToString("")).isEqualTo("PNG")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertThat(bitmap).isNotNull()
        assertThat(bitmap!!.width).isEqualTo(width)
        assertThat(bitmap.height).isEqualTo(height)
        val colours = mutableSetOf<Int>()
        val step = (width / 64).coerceAtLeast(1)
        var y = 0
        while (y < height) { var x = 0; while (x < width) { colours += bitmap.getPixel(x, y); x += step }; y += step }
        Log.i(TAG, "distinct colours in sampled grid: ${colours.size}")
        assertThat(colours.size).isGreaterThan(100)
    }

    private fun bundleDir(directoryName: String): File =
        File("/data/local/tmp/openweights/$directoryName")

    private fun requireBundle(dir: File, required: List<String>) {
        val missing = required.filterNot { File(dir, it).isFile }
        Fixtures.require("no bundle at $dir (missing $missing)", missing.isEmpty())
    }

    private fun bundle(dir: File, mnnModelType: Int) = GenerationBundle(
        id = dir.name, displayName = dir.name,
        task = GenerationTask.IMAGE, runtime = GenerationRuntime.MNN,
        files = listOf(Artifact(File(dir, "config.json").absolutePath, "application/json")),
        quantization = "fp16", minimumFreeBytes = 0,
        licence = "CreativeML OpenRAIL-M", mnnModelType = mnnModelType,
    )

    private fun memoryProbe(): String {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService<ActivityManager>()?.getMemoryInfo(info)
        return "availMem=${info.availMem / 1_000_000}MB lowMemory=${info.lowMemory}"
    }

    private companion object {
        const val TAG = "OpenWeightsGeneration"
        const val SD_DIR = "stable-diffusion-v1-5-mnn-opencl"
        const val SANA_DIR = "sana-edit-v2-mnn"
        const val PROMPT = "a lighthouse at sunset, digital painting"
        const val SD15 = 0
        const val SANA = 2
    }
}