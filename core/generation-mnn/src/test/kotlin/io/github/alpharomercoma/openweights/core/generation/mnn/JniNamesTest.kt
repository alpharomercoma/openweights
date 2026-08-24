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

package io.github.alpharomercoma.openweights.core.generation.mnn

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The two halves of a JNI call agreeing on what it is called.
 *
 * JNI does not link. `System.loadLibrary` succeeds, the library is there, every symbol is
 * exported, and the first call throws `UnsatisfiedLinkError` because a name on one side does
 * not match a name on the other. Nothing before that moment says anything: it compiles, it
 * builds, it packages, and it fails on a phone in front of somebody.
 *
 * This has already happened once here, by renaming the Kotlin class while the C++ kept the
 * old package and the old name, which is the ordinary way it happens. Reading the sources is
 * the only check that can run without a device, and it is enough: the mismatch is textual.
 */
class JniNamesTest {
    private val bridge =
        File(
            "src/main/kotlin/io/github/alpharomercoma/openweights/core/generation/mnn/MnnBridge.kt",
        )
    private val jni = File("src/main/cpp/generation_jni.cpp")

    @Test
    fun `every native function is named for the class that declares it`() {
        val declaringClass = "NativeMnn"
        val packagePath = PACKAGE.replace('.', '_')
        val expectedPrefix = "Java_${packagePath}_$declaringClass" + "_"

        val exported = EXPORTED.findAll(jni.readText()).map { it.groupValues[1] }.toList()

        assertThat(exported).isNotEmpty()
        exported.forEach { assertThat(it).startsWith(expectedPrefix) }
    }

    @Test
    fun `every external declaration has a function to resolve to`() {
        // The other direction. A Kotlin `external fun` with no counterpart is the same
        // failure arriving from the opposite side.
        val declared = EXTERNAL.findAll(bridge.readText()).map { it.groupValues[1] }.toList()
        val defined = EXPORTED.findAll(jni.readText())
            .map { it.groupValues[1].substringAfterLast('_') }
            .toSet()

        assertThat(declared).isNotEmpty()
        assertThat(defined).containsAtLeastElementsIn(declared)
    }

    @Test
    fun `the callback the native side looks up by name still exists`() {
        // `onNativeStep` is found with GetMethodID, by name and signature, once per step.
        // It is private and called from nowhere in Kotlin, so nothing else would notice it
        // being renamed until a generation reported no progress at all.
        val name = Regex("""GetMethodID\(cls, "(\w+)", "\(I\)V"\)""")
            .find(jni.readText())?.groupValues?.get(1)

        assertThat(name).isEqualTo("onNativeStep")
        assertThat(bridge.readText()).contains("private fun onNativeStep(step: Int)")
    }

    @Test
    fun `the proguard rules keep what the native side reaches by name`() {
        // R8 renames what it cannot see being used, and it cannot see any of this.
        val rules = File("consumer-rules.pro").readText()

        assertThat(rules).contains("$PACKAGE.NativeMnn")
        assertThat(rules).contains("onNativeStep")
    }

    private companion object {
        const val PACKAGE = "io.github.alpharomercoma.openweights.core.generation.mnn"
        val EXPORTED = Regex("""^(Java_\w+)\(""", RegexOption.MULTILINE)
        val EXTERNAL = Regex("""external fun (\w+)\(""")
    }
}
