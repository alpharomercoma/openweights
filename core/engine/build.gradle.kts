plugins {
    id("openweights.android.library")
}

android {
    namespace = "io.github.alpharomercoma.openweights.core.engine"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // -O3 and 16 KB-aligned segments (Play requirement for native libs).
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    // One APK, many CPUs: GGML_CPU_ALL_VARIANTS builds a set of Android
                    // CPU backends (armv8.0 up to armv9.2 + SME) and each one reports at
                    // runtime whether this chip supports it. BackendSelector picks the
                    // highest-scoring variant, so a phone with i8mm gets i8mm matmuls and
                    // an older phone still runs. Requires GGML_BACKEND_DL, which in turn
                    // requires shared libraries.
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_CPU_KLEIDIAI=ON",
                    // GPU backends are deliberately not built yet. ggml's Vulkan target
                    // needs vendored SPIRV-Headers and a host shader compiler, and on the
                    // Mali-class GPUs we have measured it loses to the tuned CPU path.
                    // The engine enumerates backends at runtime, so turning this on later
                    // makes the GPU option appear in Settings with no other code change.
                    // "-DGGML_VULKAN=ON", "-DVulkan_GLSLC_EXECUTABLE=" + glslcPath,
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    // libmtmd: the multimodal projector runtime. It turns images, audio
                    // and video frames into embeddings the language model can attend over.
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DLLAMA_BUILD_SERVER=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    ndkVersion = "29.0.14206865"

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
