plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-openai"))
    implementation(project(":provider-gemini"))
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }

application { mainClass.set("dev.typedllm.bench.BenchRunnerKt") }

// `./gradlew :bench:run --args="..."` runs from kotlin-library/bench; make relative paths start at the repo root instead.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir.parentFile }
