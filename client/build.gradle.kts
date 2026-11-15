import java.nio.file.Files

plugins {
    kotlin("jvm")
    application
}
dependencies {
    implementation(project(":core"))
    implementation(project(":server"))
}
application { mainClass.set("city.client.ClientMainKt") }

// Real JFrame smoke test, run under a display (e.g. xvfb-run).
tasks.register<JavaExec>("uiSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("city.client.UiSmokeKt")
    systemProperty("java.awt.headless", "false")
}

// Isolate the malformed plugin and the local save from real user data.
tasks.register<JavaExec>("uiStartupSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("city.client.PluginStartupSmokeKt")
    systemProperty("java.awt.headless", "false")
    doFirst {
        workingDir = Files.createTempDirectory("city-startup-smoke").toFile()
    }
}
