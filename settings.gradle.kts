pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.10"
        id("com.android.application") version "9.0.1"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "pure-ledger"
include(":core")
if (providers.gradleProperty("coreOnly").orNull != "true") include(":app")
