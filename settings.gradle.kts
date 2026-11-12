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

rootProject.name = "ScreenKit"
include(":app")

include(":stream-common", ":stream-rtmp")
project(":stream-common").projectDir = file("third_party/rootencoder/common")
project(":stream-rtmp").projectDir = file("third_party/rootencoder/rtmp")
