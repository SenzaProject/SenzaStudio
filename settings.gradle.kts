pluginManagement {
    repositories {
        google()
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

rootProject.name = "SenzaStudio"

include(
    ":app",
    ":core-design",
    ":audio-engine",
    ":feature-studio",
    ":feature-equalizer",
    ":feature-spatial",
    ":feature-presets",
    ":feature-settings",
)
