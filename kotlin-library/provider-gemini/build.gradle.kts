plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    // Only for CompletableFuture.await(); HTTP itself uses the JDK's built-in client.
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    // Let the live tests' println output show in the console.
    testLogging { showStandardStreams = true }
}
