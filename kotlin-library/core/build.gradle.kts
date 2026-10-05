plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Exposed in the public API (KSerializer, JsonObject), so consumers need it too.
    api(libs.serialization.json)
    implementation(libs.kaml)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
