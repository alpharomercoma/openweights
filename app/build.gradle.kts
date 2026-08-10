import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("openweights.android.application")
    id("openweights.android.compose")
    id("openweights.android.hilt")
}

android {
    namespace = "io.github.alpharomercoma.openweights"

    defaultConfig {
        applicationId = "io.github.alpharomercoma.openweights"
        versionCode = 1
        versionName = "0.1.0"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Play requires 16 KB-aligned uncompressed shared libraries.
        jniLibs.useLegacyPackaging = false
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // Downloads outlive the screen that started them, so they run as WorkManager jobs and
    // the worker is built by Hilt rather than by the default factory that cannot inject.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
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
    val artifacts = fileTree(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    } + fileTree(layout.buildDirectory.dir("outputs/bundle/release")) {
        include("*.aab")
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

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    finalizedBy("verifyJniSymbols")
}
