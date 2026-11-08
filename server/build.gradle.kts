plugins {
    kotlin("jvm")
    application
}
dependencies { implementation(project(":core")) }
application { mainClass.set("city.server.ServerMainKt") }

// Load the real example JAR through ServiceLoader during integration tests.
tasks.test {
    dependsOn(":example-plugin:jar")
    systemProperty(
        "examplePluginJar",
        project(":example-plugin")
            .layout.buildDirectory
            .file("libs/example-plugin-0.1.0.jar")
            .get()
            .asFile.absolutePath,
    )
}
