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
                    // Vulkan stays off, and now for a measured reason rather than a
                    // remembered one. Built for a Mali-G925 and benchmarked against the
                    // same model on the same tool: decode 39.2 t/s against the CPU's 36.3,
                    // and prefill 15.4 against 131.1. An 8% gain on decode cannot pay for
                    // prefill running 8.5 times slower, and prefill is every token of the
                    // prompt, the conversation and any tool output, on every turn — about
                    // four times worse per turn overall. Numbers, method and the three
                    // headers the NDK does not supply are in
                    // docs/research/gpu-backends.md.
                    //
                    // The engine enumerates backends at runtime, so turning it on later
                    // needs no other code change.
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

/**
 * The architectures the engine in this build can actually load.
 *
 * Read out of the submodule's own `LLM_ARCH_NAMES` rather than written down anywhere, so
 * it cannot drift from the library being compiled beside it: bumping llama.cpp updates
 * this list in the same commit, and forgetting to update it is not a thing that can
 * happen.
 *
 * llama.cpp exposes no public API for this. `llm_arch_from_string` is internal and
 * `llama.h` says nothing about architectures at all, so the table is the only source, and
 * reading the source we compile is closer to the truth than any list we could maintain.
 */
abstract class GenerateEngineArchitectures : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archSource: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val table = archSource.get().asFile.readText()
            .substringAfter("LLM_ARCH_NAMES = {")
            .substringBefore("\n};")
        val entries = Regex("""\{\s*LLM_ARCH_[A-Z0-9_]+\s*,\s*"([^"]+)"\s*\}""")
            .findAll(table)
            .map { it.groupValues[1] }
            .toList()

        // Two guards, because each one alone has a hole.
        //
        // Every line of that table carries exactly one LLM_ARCH_ token, so counting them
        // gives the number of entries independently of whether the regex understood their
        // shape. Requiring the two to agree catches a partial parse, which a floor alone
        // does not: "more than a hundred" passes happily on 120 of 145 and silently tells
        // users that 25 working architectures cannot be loaded.
        //
        // The floor catches what the agreement alone does not. `substringAfter` returns
        // the whole input when its delimiter is absent, so a reformatted declaration can
        // leave a slice holding no LLM_ARCH_ tokens at all, and 0 == 0 is agreement. That
        // ships an empty set, which reads as "this app can load nothing" and would refuse
        // every download in Discover while the build stayed green.
        val declared = Regex("LLM_ARCH_[A-Z0-9_]+").findAll(table).count()
        // 100 is well under the 145 the table holds today and far above any partial parse.
        require(entries.size == declared && entries.size >= 100) {
            "Parsed ${entries.size} of $declared architectures from llama-arch.cpp. " +
                "The LLM_ARCH_NAMES table changed shape; fix the pattern in this task."
        }

        // This set answers one question: can this file be loaded as a chat model.
        //
        // `clip` is dropped even though it is a real architecture, and this is the subtle
        // one. It is what every multimodal projector declares, llama.cpp's own constants
        // calling it "dummy arch for clip.cpp". A projector holds a vision or audio
        // encoder and no language model, so a file declaring `clip` cannot answer a
        // question however well the engine reads it. Projectors reach the app by name,
        // `mmproj-*`, and a projector named something else would otherwise be offered as
        // a model and fail on load. Dropping `clip` here is what catches it.
        //
        // `(unknown)` is the name of the absent value and no file declares it.
        //
        // Filtered after the count above, so dropping them cannot be mistaken for a parse
        // that came up short.
        // Draft heads, dropped for exactly the reason `clip` is.
        //
        // A speculative-decoding draft is published beside the model it drafts for and
        // looks like an ordinary model from the outside: LiquidAI's DSpark draft declares
        // `dflash`, weighs 168 MB, loads without complaint and fits any phone comfortably.
        // It holds no vocabulary and no output layer — it borrows both from its target —
        // so it cannot answer a question however well the engine reads it. llama.cpp says
        // so at the last possible moment, refusing the context with "requires ctx_other to
        // be set", by which point somebody has chosen it as their model.
        //
        // Named here rather than derived, because nothing in llama-arch.cpp says which
        // architectures are drafts; the table is names, and this is what they are for.
        val drafts = setOf("dflash", "eagle3")
        val names = entries
            .filter { it != "clip" && it !in drafts && !it.startsWith("(") }
            .toSortedSet()
        val draftNames = entries.filter { it in drafts }.toSortedSet()

        val destination = outputDir.get().asFile
            .resolve("io/github/alpharomercoma/openweights/core/engine/EngineArchitectures.kt")
        destination.parentFile.mkdirs()
        destination.writeText(
            buildString {
                appendLine("// Generated from llama.cpp/src/llama-arch.cpp. Do not edit.")
                appendLine()
                appendLine("package io.github.alpharomercoma.openweights.core.engine")
                appendLine()
                appendLine("/**")
                appendLine(" * The architectures this APK's llama.cpp can load as a chat model.")
                appendLine(" *")
                appendLine(" * Not every architecture it registers. `clip`, which every")
                appendLine(" * multimodal projector declares, is deliberately absent, and so")
                appendLine(" * are the speculative-decoding draft heads in [DRAFT]: both are")
                appendLine(" * files the engine reads happily and neither can answer a")
                appendLine(" * question. Ask this about a file somebody is about to download")
                appendLine(" * as a model.")
                appendLine(" */")
                appendLine("public object EngineArchitectures {")
                appendLine("    public val SUPPORTED: Set<String> = setOf(")
                names.forEach { appendLine("        \"" + it + "\",") }
                appendLine("    )")
                appendLine()
                appendLine("    /**")
                appendLine("     * Whether this build can load [architecture] as a chat model.")
                appendLine("     *")
                appendLine("     * Blank comes back true. An architecture nobody could read is")
                appendLine("     * not a refusal, and a check that cannot see must not block.")
                appendLine("     */")
                appendLine("    public fun supports(architecture: String): Boolean =")
                appendLine("        architecture.isBlank() ||")
                appendLine("            architecture.lowercase() in SUPPORTED")
                appendLine()
                appendLine("    /**")
                appendLine("     * Draft heads for speculative decoding.")
                appendLine("     *")
                appendLine("     * Held apart from the unknown architectures rather than")
                appendLine("     * lumped in with them, because the two need opposite things")
                appendLine("     * said about them: an unknown architecture is a reason to")
                appendLine("     * update the app, and no version of this app will ever run")
                appendLine("     * one of these on its own.")
                appendLine("     */")
                appendLine("    public val DRAFT: Set<String> = setOf(")
                draftNames.forEach { appendLine("        \"" + it + "\",") }
                appendLine("    )")
                appendLine()
                appendLine("    /** Whether [architecture] is a draft head rather than a model. */")
                appendLine("    public fun isDraft(architecture: String): Boolean =")
                appendLine("        architecture.lowercase() in DRAFT")
                appendLine("}")
            },
        )
    }
}

val generateEngineArchitectures =
    tasks.register<GenerateEngineArchitectures>("generateEngineArchitectures") {
        archSource.set(layout.projectDirectory.file("src/main/cpp/llama.cpp/src/llama-arch.cpp"))
    }

// The variant API rather than android.sourceSets: the source-set accessor a library
// module gets in Kotlin DSL cannot be cast to the type AGP hands back, and this is the
// supported way to attach generated sources regardless.
androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateEngineArchitectures,
            GenerateEngineArchitectures::outputDir,
        )
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
