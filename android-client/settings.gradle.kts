pluginManagement {
    includeBuild("build-logic")
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
        // WebRTC Android SDK
        maven { url = uri("https://jitpack.io") }
        flatDir {
            dirs("libs")
        }
    }
}
rootProject.name = "IMS-DC-Demo"
include(":app")
include(":ims-dc-sdk")
include(":base")
include(":nativelibs")
include(":oemec")
