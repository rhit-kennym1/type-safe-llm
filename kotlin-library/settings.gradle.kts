rootProject.name = "typed-llm"

// Where every module downloads its dependencies from, declared once for the whole build.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("core", "provider-openai", "demo-ui")
