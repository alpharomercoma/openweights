plugins {
    // Applied by id rather than by catalog alias: the Android plugins reach this build
    // through build-logic's classpath, where Gradle cannot see their version, and asking
    // for one by version is then refused. Kotlin needs no plugin of its own: AGP 9 brings it.
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

/*
 * The module that writes the profile the app ships with.
 *
 * A test module rather than a library: it drives the real app on a real device and records
 * which methods ran, so ART can compile them ahead of time instead of interpreting them on
 * the first launch. Nothing here is shipped; its output is.
 */
android {
    namespace = "io.github.alpharomercoma.openweights.baselineprofile"
    // Spelled out rather than taken from BuildConfig: this module is not on build-logic's
    // classpath, and one number is a smaller price than putting it there for a module that
    // ships nothing.
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark needs 28; the app needs 31, and the profile has to be recorded
        // against what the app actually runs on.
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Profiles have to be recorded against a build that resembles the shipped one, and the
    // plugin makes that variant itself. Pointing at it here rather than at debug is what
    // stops the profile describing code R8 has since renamed.
    targetProjectPath = ":app"

    // The app carries a runtime flavour, and a test module that names none cannot pick
    // a variant of it: `generateBaselineProfile` failed to resolve `:app` at all from the
    // day the flavour landed, so the shipped profile could no longer be regenerated. The
    // standard flavour is the one that profiles the code both flavours share.
    flavorDimensions += "runtime"
    productFlavors {
        create("standard") { dimension = "runtime" }
        create("accelerated") { dimension = "runtime" }
    }
}

baselineProfile {
    // Written into the app's own sources rather than kept per variant, because there is one
    // variant that ships and a profile is only worth having in it.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.benchmark.macro.junit4)
}
