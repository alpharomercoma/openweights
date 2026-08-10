import java.util.Properties

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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

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
