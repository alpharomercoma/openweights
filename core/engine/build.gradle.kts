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
                    // The Adreno GPU backend, through OpenCL. Measured on a Snapdragon 8
                    // Elite (Adreno 830): prefill runs 4.8 to 5.5 times faster than the
                    // tuned CPU path, and decode runs about 0.7 times as fast, because
                    // reading one token at a time is bound by memory bandwidth rather than
                    // by compute. So it is built in and offered, not switched on by
                    // default. Kernels are embedded rather than loaded from files, since
                    // an APK has no directory to read them from.
                    "-DGGML_OPENCL=ON",
                    "-DGGML_OPENCL_EMBED_KERNELS=ON",
                    "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON",
                    // Vulkan stays off: its ggml target needs vendored SPIRV-Headers and a
                    // host shader compiler, and on the Mali-class GPUs we have measured it
                    // loses to the tuned CPU path. The engine enumerates backends at
                    // runtime, so turning it on later needs no other code change.
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
        // Khronos's ICD loader is a link target, not a runtime one. Qualcomm lists
        // libOpenCL.so in /vendor/etc/public.libraries.txt, so the app resolves the
        // driver already on the phone. Shipping ours would shadow it, and ours reads
        // /vendor/etc/OpenCL/vendors, which Android devices do not have: the result is a
        // GPU backend that loads, reports no platforms, and silently runs on the CPU.
        jniLibs.excludes += "**/libOpenCL.so"
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
