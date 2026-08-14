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

    // A deadline, because a hung test is worse than a failing one. A sampler that looped
    // forever under a virtual-time scheduler turned four `verify` runs into an hour each,
    // and every run that was killed left its worker behind spinning a core, so the machine
    // got slower with each attempt until nothing on it was quick. Five minutes is far more
    // than the whole suite needs and far less than it takes to notice by hand.
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(5))
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
    // Matched by name as each subproject configures itself, not read out of the task
    // container now: findByName here returns null for anything the Android plugin has not
    // created yet, which silently drops whole tiers from the run.
    dependsOn(
        VERIFY_TASKS.map { name ->
            subprojects.map { project -> project.tasks.matching { it.name == name } }
        },
    )
}

/** The host-side tiers, in the order they fail fastest. */
val VERIFY_TASKS = listOf(
    "ktlintCheck",
    "detekt",
    // Android lint, on the variant that ships. It had never been run before 2026-08-10 and
    // was holding four errors, including composables reading the locale in a way that
    // ignores the user changing it. Run it on release, because that is the variant with
    // R8 and the manifest that reaches Play.
    "lintRelease",
    "assembleDebug",
    // Compiled here even though it cannot be run here. Nothing in this tier builds the
    // instrumentation sources, so they rotted quietly: a composable gained two parameters
    // and the screen test that calls it had not compiled since, which nobody found out
    // until the next person needed to run it. Compiling costs seconds and is the whole
    // difference between a device tier that works when reached for and one that does not.
    "assembleDebugAndroidTest",
    "testDebugUnitTest",
)

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
    dependsOn(
        subprojects.map { project ->
            project.tasks.matching { it.name == "connectedDebugAndroidTest" }
        },
    )
}
