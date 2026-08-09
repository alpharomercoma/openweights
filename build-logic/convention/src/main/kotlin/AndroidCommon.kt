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

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Values shared by every Android module in the project. */
object BuildConfig {
    /** Latest SDK we compile against; current AndroidX requires 37+. */
    const val COMPILE_SDK = 37

    /** Play requires 36+ for new apps from 2026-08-31; bump deliberately, not by accident. */
    const val TARGET_SDK = 36
    const val MIN_SDK = 31

    /** The only ABI we ship: 32-bit ARM is irrelevant for LLM inference. */
    const val ABI = "arm64-v8a"

    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    val JVM_TARGET: JvmTarget = JvmTarget.JVM_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Applies the SDK levels, Java/Kotlin targets, and ABI filter every module shares. */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.compileSdk = BuildConfig.COMPILE_SDK
    extension.defaultConfig.minSdk = BuildConfig.MIN_SDK
    extension.defaultConfig.ndk.abiFilters += BuildConfig.ABI

    extension.compileOptions.sourceCompatibility = BuildConfig.JAVA_VERSION
    extension.compileOptions.targetCompatibility = BuildConfig.JAVA_VERSION

    // Robolectric reads the merged manifest and resources, so unit tests can drive real
    // Android objects. Returning defaults instead of throwing keeps a stubbed framework
    // call from failing a test that is not about that call.
    extension.testOptions.unitTests.isIncludeAndroidResources = true
    extension.testOptions.unitTests.isReturnDefaultValues = true

    extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions {
        jvmTarget.set(BuildConfig.JVM_TARGET)
        freeCompilerArgs.addAll("-Xconsistent-data-class-copy-visibility")
    }
}
