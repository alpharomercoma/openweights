/*
 * MNN behind the generation interfaces, and the native build that makes it real.
 *
 * Its own module rather than a source set inside `:core:generation`, because that module's
 * whole point is that it is pure Kotlin an iPhone can compile: putting a JNI bridge and ten
 * thousand files of vendored C++ behind the same name would undo the split the moment
 * anything depended on it. Here, the chat path and the iOS target never see it.
 *
 * ### The native build is opt in, and that is not laziness
 *
 * MNN with diffusion and OpenCL takes about two minutes on ten cores and produces 6.5 MB of
 * stripped arm64 libraries. Paying that on every `assembleDebug` would put two minutes
 * between a one-line change and knowing whether it compiled, for a feature most builds do
 * not touch, and the APK cost would be carried by every install whether or not a bundle is
 * ever downloaded.
 *
 * So it builds when asked: `-Popenweights.mnn=true`, or the same property in a local
 * `gradle.properties`. Without it this module still compiles, still has its tests, and its
 * generator reports that this build has no image runtime, which is the same answer it gives
 * when the libraries are present and no bundle is installed. Nothing pretends.
 */
plugins {
    id("openweights.android.library")
    id("openweights.android.hilt")
}

/** Set `openweights.mnn=true` to compile MNN and the JNI bridge into this module. */
val buildsNative = providers.gradleProperty("openweights.mnn").orNull == "true"

android {
    namespace = "io.github.alpharomercoma.openweights.core.generation.mnn"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (buildsNative) {
            externalNativeBuild {
                cmake {
                    // Exceptions are not optional here: cancelling a run means throwing out
                    // of MNN's progress callback, which is the only interruption point the
                    // denoising loop has.
                    cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                }
            }
            ndk {
                // arm64 only, on purpose. Every ABI is another 6.5 MB and another two
                // minutes, and a phone that can hold a diffusion model in memory is a
                // 64-bit phone. Play has required a 64-bit build since 2019.
                abiFilters += "arm64-v8a"
            }
        }
    }

    if (buildsNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        // The same reason the chat engine excludes it: Qualcomm publishes libOpenCL.so
        // through /vendor/etc/public.libraries.txt, so the driver on the phone resolves.
        // Shipping ours would shadow it and silently drop every generation onto the CPU.
        jniLibs.excludes += "**/libOpenCL.so"
    }

    buildTypes.configureEach {
        buildConfigField("boolean", "HAS_MNN_NATIVE", buildsNative.toString())
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:generation"))
    implementation(libs.kotlinx.coroutines.android)
}
