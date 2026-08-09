pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenWeights"

include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:engine")
// Modules added as their phase lands: :core:hub + :core:device (P2), :core:data (P3).
// See docs/CONTEXT.md for phase status.
