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
