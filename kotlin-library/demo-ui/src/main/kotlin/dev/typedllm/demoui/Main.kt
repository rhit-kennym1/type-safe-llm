package dev.typedllm.demoui

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

private const val PORT = 8080

fun main() {
    println("typed-llm demo running at http://localhost:$PORT")
    embeddedServer(Netty, port = PORT, module = { demoModule() }).start(wait = true)
}

/** Wires the demo together. Kept separate from [main] so tests can start it in memory with their own config. */
fun Application.demoModule(live: LiveConfig = LiveConfig.fromEnvironment()) {
    install(ContentNegotiation) { json() }
    configureRouting(DemoService(live))
}
