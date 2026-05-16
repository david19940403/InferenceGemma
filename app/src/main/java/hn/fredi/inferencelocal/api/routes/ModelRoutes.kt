package hn.fredi.inferencelocal.api.routes

import hn.fredi.inferencelocal.api.*
import hn.fredi.inferencelocal.api.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.modelRoutes() {
    get("/api/tags") {
        val registry = requireRegistry()
        call.respond(HttpStatusCode.OK, registry.listModels())
    }

    post("/api/show") {
        val registry = requireRegistry()
        val req = safeReceive<ShowRequest>() ?: return@post
        val info = registry.showModel(req.name)
        if (info == null) {
            call.respond(HttpStatusCode.NotFound, OllamaError("model '${req.name}' not found"))
        } else {
            call.respond(HttpStatusCode.OK, info)
        }
    }

    post("/api/create") {
        call.respond(HttpStatusCode.NotImplemented, OllamaError("Creating models via API is not yet supported. Please add GGUF files to the models directory."))
    }

    delete("/api/delete") {
        val registry = requireRegistry()
        val req = safeReceive<DeleteRequest>() ?: return@delete
        if (registry.deleteModel(req.name)) {
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, OllamaError("model '${req.name}' not found or could not be deleted"))
        }
    }
    
    // Alias for delete to match some clients
    post("/api/delete") {
        val registry = requireRegistry()
        val req = safeReceive<DeleteRequest>() ?: return@post
        if (registry.deleteModel(req.name)) {
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, OllamaError("model '${req.name}' not found or could not be deleted"))
        }
    }

    get("/api/ps") {
        val registry = requireRegistry()
        call.respond(HttpStatusCode.OK, registry.listRunningModels())
    }

    get("/api/version") {
        val registry = requireRegistry()
        call.respond(HttpStatusCode.OK, registry.version())
    }
}
