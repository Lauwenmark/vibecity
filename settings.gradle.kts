pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "shared-city-2000"
include("core", "server", "client", "example-plugin")
