plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

subprojects {
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
        android.set(true)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }
}

/**
 * Everything that can be checked without a phone plugged in.
 *
 * Three tiers, one command. Static analysis catches what the compiler will not, the unit
 * tests cover pure logic, and the Robolectric tests drive the real database and view
 * models on the host. The device tier is separate on purpose: it needs weights and
 * hardware, and nothing that can be checked without those should need them.
 */
tasks.register("verify") {
    group = "verification"
    description = "Static analysis, unit tests and host integration tests."
    dependsOn(
        subprojects.mapNotNull { it.tasks.findByName("assembleDebug") },
        subprojects.mapNotNull { it.tasks.findByName("ktlintCheck") },
        subprojects.mapNotNull { it.tasks.findByName("detekt") },
        subprojects.mapNotNull { it.tasks.findByName("testDebugUnitTest") },
    )
}

/**
 * The device tier: the engine against real weights.
 *
 * Kept out of [verify] because it needs a phone, model files pushed to
 * /data/local/tmp/openweights, and several minutes. See docs/CONTEXT.md for the setup and
 * for the standalone native probe, which is faster when iterating on the C++ side.
 */
tasks.register("verifyOnDevice") {
    group = "verification"
    description = "Instrumented engine tests. Needs a connected device and model files."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("connectedDebugAndroidTest") })
}
