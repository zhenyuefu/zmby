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
rootProject.name = "zmby"
include(":app")
// Emulator stand-in for the Zidoo player; see tools/mock_emby.py.
include(":devtools:fake-zidoo-player")
