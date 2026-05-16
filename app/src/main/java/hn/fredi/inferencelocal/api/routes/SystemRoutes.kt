package hn.fredi.inferencelocal.api.routes

import hn.fredi.inferencelocal.api.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.systemRoutes() {
    get("/") {
        call.respondText("Ollama is running")
    }

    get("/api/mcp") {
        val toolManager = call.application.attributes[ToolManagerKey]
        call.respond(HttpStatusCode.OK, toolManager.getMcpServers())
    }

    post("/api/mcp/refresh") {
        // MCP refresh not fully implemented in current ToolManager
        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "MCP refresh not implemented"))
    }
}
