plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
}
