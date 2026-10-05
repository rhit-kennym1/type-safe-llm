package dev.typedllm.demoui

import dev.typedllm.ProviderException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private const val MAX_MESSAGE_CHARS = 2_000

fun Application.configureRouting(service: DemoService) {
    routing {
        route("/api") {
            get("/target") { call.respond(service.target) }

            get("/scenarios") { call.respond(service.scenarios()) }
            post("/scenarios/{id}/run") {
                val result = service.runScenario(call.parameters["id"].orEmpty())
                if (result == null) call.respond(HttpStatusCode.NotFound) else call.respond(result)
            }

            get("/live/models") { call.respond(service.liveModels()) }
            post("/live/run") {
                val request = call.receive<LiveRunRequest>()
                if (request.message.isBlank() || request.message.length > MAX_MESSAGE_CHARS) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("Enter a message under $MAX_MESSAGE_CHARS characters."))
                    return@post
                }
                try {
                    val result = service.runLive(request.message, request.modelId)
                    if (result == null) call.respond(HttpStatusCode.NotFound) else call.respond(result)
                } catch (e: ProviderException) {
                    call.respond(HttpStatusCode.BadGateway, ApiError(e.message ?: "The model provider failed."))
                }
            }
        }
        staticResources("/", "static")
    }
}
