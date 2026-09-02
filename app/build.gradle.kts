import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("openweights.android.application")
    id("openweights.android.compose")
    id("openweights.android.hilt")
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * The number of commits on this branch, used as the version code.
 *
 * Play's one rule for a version code is that it must be higher than the last one uploaded,
 * ever, and it is not recoverable: a code that has been used is used, and a build that
 * repeats one is rejected at the door. Typing it by hand is therefore a promise to remember
 * something, indefinitely, while doing something else, and the failure mode is finding out
 * at upload time with a bundle already built.
 *
 * The commit count is the cheapest thing that cannot go backwards. It needs no service
 * account, no secret, and no state anywhere outside the repository, and it produces the same
 * answer on this laptop as in CI, which a build-number counter does not: a workflow renamed
 * or recreated resets that counter, and a version code that goes down cannot be undone.
 *
 * Two things to know about it.
 *
 * It is per branch, so a release must be cut from `main`. A build from a branch with fewer
 * commits produces a lower code, which Play will refuse rather than accept, so the failure
 * is loud.
 *
 * And it is wrong on a shallow clone, quietly, which is the one that would actually have
 * bitten: `actions/checkout` fetches a single commit by default, so a naive count returns 1
 * in CI while returning a hundred and something locally. That is why the shallow case throws
 * rather than falling back, and why the workflow asks for the full history.
 */
val gitCommitCount: Int = run {
    val git = { args: List<String> ->
        runCatching {
            providers.exec {
                commandLine(args)
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    if (git(listOf("git", "rev-parse", "--is-shallow-repository")) == "true") {
        throw GradleException(
            "This is a shallow clone, so the commit count is not the real one and the " +
                "version code built from it would be wrong. Fetch the full history " +
                "(actions/checkout with fetch-depth: 0) and build again.",
        )
    }

    val counted = git(listOf("git", "rev-list", "--count", "HEAD"))?.toIntOrNull()

    // Absent git entirely, which is a source archive rather than a checkout. One is the
    // lowest code Play accepts and nothing built this way is publishable anyway. Only that
    // case falls back: a checkout that has git and still cannot be counted is a broken
    // build, not a source archive, and the difference decides what gets uploaded.
    if (counted == null && rootProject.file(".git").exists()) {
        throw GradleException(
            "There is a .git here but the commit count could not be read, so the version " +
                "code would silently be 1. Build again and check that git runs.",
        )
    }
    counted?.takeIf { it > 0 } ?: 1
}

android {
    namespace = "io.github.alpharomercoma.openweights"

    defaultConfig {
        applicationId = "io.github.alpharomercoma.openweights"
        // Counted, not typed. See gitCommitCount.
        versionCode = gitCommitCount
        // Typed, not counted, and deliberately the other way round from the line above. A
        // version name is editorial: it says how big a change this is, which is a judgement
        // no tool can make. A version code is a counter Play uses to order uploads and
        // nothing else, and the one requirement on it is that it never repeats, which is
        // exactly the kind of promise a person forgets and a machine does not.
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Off by default since AGP 8, and About needs the version name it generates. Nothing
    // else here reads BuildConfig, so this exists to let one screen say which build it is.
    buildFeatures { buildConfig = true }

    /*
     * Release signing.
     *
     * Read from a properties file or the environment, never from the repository. Play App
     * Signing holds the key that users verify against; this is only the upload key, but an
     * upload key in git is still an upload key anyone can use.
     *
     * Local:  keystore.properties in the project root, git-ignored.
     * CI:     OPENWEIGHTS_KEYSTORE and friends in the environment.
     *
     * Absent, the release build is simply unsigned, which is what a contributor building
     * from a fresh clone should get rather than a confusing failure.
     */
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    fun secret(key: String, env: String): String? =
        keystoreProperties.getProperty(key) ?: System.getenv(env)

    val storePath = secret("storeFile", "OPENWEIGHTS_KEYSTORE")

    signingConfigs {
        if (storePath != null) {
            create("release") {
                // Resolved against the root, where keystore.properties lives. file()
                // here would resolve a relative path under app/ instead.
                storeFile = rootProject.file(storePath)
                storePassword = secret("storePassword", "OPENWEIGHTS_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "OPENWEIGHTS_KEY_ALIAS")
                keyPassword = secret("keyPassword", "OPENWEIGHTS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    /**
     * Mirrors core:engine's dimension, which is where the second runtime actually lives.
     * The accelerated variant carries ExecuTorch and can open a `.pte`; the standard one
     * ships llama.cpp alone and never offers models it could not run.
     */
    flavorDimensions += "runtime"
    productFlavors {
        create("standard") { dimension = "runtime" }
        create("accelerated") {
            dimension = "runtime"
            versionNameSuffix = "-accelerated"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Play requires 16 KB-aligned uncompressed shared libraries.
        jniLibs.useLegacyPackaging = false

        // Two copies of the C++ runtime arrive in the APK: ours, from the CMake builds in
        // core:engine and core:sandbox, and a prebuilt one inside fbjni, which ExecuTorch
        // depends on for its JNI. They are the same library from different NDKs, and the
        // merger will not choose between them.
        //
        // Taking the first is what every project carrying fbjni does. It is safe because
        // libc++_shared is backward compatible and both are recent, but it is a real
        // decision rather than boilerplate: if ExecuTorch ever ships against an NDK newer
        // than ours, the copy that wins could be older than the one its .so was linked
        // against, and the symptom would be a link error at load rather than here.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:engine"))
    implementation(project(":core:hub"))
    implementation(project(":core:tools"))
    implementation(project(":core:device"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    // Coil 3 split network loading into its own artifact. Without it AsyncImage has no
    // fetcher for an https model, fails silently, and every publisher tile in Discover fell
    // back to drawing initials: the avatars were being looked up correctly and thrown away.
    implementation(libs.coil.network.okhttp)
    // Hugging Face gives an account that never uploaded a picture a generated identicon,
    // served from /avatars/<hash>.svg. Coil decodes no SVG without this artifact, so those
    // publishers drew an empty slot: on a trending page of GGUF repositories that is nine
    // of the thirty-eight individual accounts and none of the nineteen organisations,
    // which is why it read as personal publishers having no logo at all.
    implementation(libs.coil.svg)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Downloads outlive the screen that started them, so they run as WorkManager jobs and
    // the worker is built by Hilt rather than by the default factory that cannot inject.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    // Installs the shipped profile on the devices whose Play Store does not do it for us,
    // which is most of them below API 31 and any sideloaded build. Without it the profile
    // is carried and never applied, which is the quiet way to have done this work twice.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)

    // Not shipped: the module that records the profile above by driving the app on a device.
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.room.testing)
    // A real WorkManager on the host, so ModelsViewModel can be driven rather than mocked:
    // the behaviour worth testing there is what it does with the queue's emissions, and a
    // fake queue would be a fake of the exact thing that went wrong.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp)
    // The catalogue test builds the real tool set to check every schema a model is shown,
    // which means reaching the two things core:tools keeps to itself.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:sandbox"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.okhttp)
    // Only the benchmark reaches the sandbox directly; core:tools keeps it internal.
    androidTestImplementation(project(":core:sandbox"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * Proves the names JNI resolves at runtime survived R8.
 *
 * A release build once aborted the process on the first token of every reply because a keep
 * rule said `allowobfuscation` and R8 duly renamed `onToken`, the one string the native side
 * looks up. No debug build can catch that, because R8 does not run there, and the checklist
 * that said to re-test the release build by hand did not get followed.
 *
 * So it is a build step now. Dex stores method names as plain UTF-8, so this reads the
 * shipped artifact rather than the rules that were meant to protect it: it fails on what
 * actually got built, not on what was intended.
 */
val jniSymbols = listOf(
    // Resolved by GetMethodID during generation. The class names are free to change,
    // because native code reaches them through GetObjectClass.
    "onToken",
    "onReply",
    // Resolved by the dynamic linker as Java_..._LlamaBridge_nativeGenerate and friends,
    // so for these the package and class name have to survive as well.
    "nativeGenerate",
    "nativeLoadModel",
    "LlamaBridge",
    "LlamaException",
)

tasks.register("verifyJniSymbols") {
    group = "verification"
    description = "Fails if R8 renamed or removed a name the native library resolves."

    // Both artifacts, because they are not the same file and only one of them is what
    // Play receives. The guard was written against the APK, which is the one nobody
    // uploads: bundleRelease produces the AAB, and it was going out unchecked.
    // Under the flavour directories: outputs/apk/standard/release, outputs/bundle/
    // standardRelease and their accelerated twins. The pre-flavour paths matched a stale
    // APK and AAB left from before the split, so the guard passed against artifacts that
    // were not the ones being shipped, and on a clean checkout found nothing at all.
    val artifacts = fileTree(layout.buildDirectory.dir("outputs/apk")) {
        include("*/release/*.apk")
    } + fileTree(layout.buildDirectory.dir("outputs/bundle")) {
        include("*Release/*.aab")
    }
    val symbols = jniSymbols
    inputs.files(artifacts)

    doLast {
        val built = artifacts.files.filter { it.isFile }
        check(built.isNotEmpty()) {
            "Nothing to check. Run assembleRelease or bundleRelease first."
        }
        built.forEach { artifact ->
            val found = mutableSetOf<String>()
            ZipFile(artifact).use { zip ->
                zip.entries().asSequence()
                    // An AAB keeps its dex under base/dex/ rather than at the root, so
                    // match on the extension alone and both layouts are covered.
                    .filter { it.name.endsWith(".dex") }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).readBytes()
                        val text = String(bytes, Charsets.ISO_8859_1)
                        symbols.forEach { if (text.contains(it)) found += it }
                    }
            }
            val missing = symbols - found
            check(missing.isEmpty()) {
                "R8 removed or renamed names the native library resolves by string, in " +
                    "${artifact.name}: $missing. Generation would abort the process at " +
                    "runtime. Check core/engine/consumer-rules.pro."
            }
            logger.lifecycle(
                "verifyJniSymbols: all ${symbols.size} names survived R8 in ${artifact.name}",
            )
        }
    }
}

// The per-flavour tasks as well as the aggregates: `bundleStandardRelease` is the one
// that produces what Play receives, and it was not on this list.
tasks.matching {
    Regex("(assemble|bundle)(Standard|Accelerated)?Release").matches(it.name)
}.configureEach {
    finalizedBy("verifyJniSymbols")
}
