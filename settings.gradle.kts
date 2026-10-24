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

rootProject.name = "DetectiveCases"

// :core    the party platform: host phone server, QR, relay, self-update, web shell (reused by every app)
// :casekit the detective-case engine + generic case screens (reused by every case)
// :hub     the APK: picks the cases to host and brings their content
// :dev     desktop launcher (no Android needed)
include(":core", ":casekit", ":hub", ":dev")
