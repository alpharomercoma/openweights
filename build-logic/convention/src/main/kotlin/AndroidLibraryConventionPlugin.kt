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

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // AGP 9 compiles Kotlin itself; org.jetbrains.kotlin.android must NOT be applied.
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            // Rules a module needs in order to survive R8 travel with the module, so the
            // app does not have to know which of its dependencies has a native half or
            // uses reflection. Missing when the file is absent, which is most modules.
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            // Robolectric so Room, DataStore and view models can be exercised on the host.
            // The device tier still exists for the engine, but nothing that can be checked
            // without hardware should need hardware.
            add("testImplementation", libs.findLibrary("robolectric").get())
            add("testImplementation", libs.findLibrary("androidx-test-ext-junit").get())
        }
    }
}
