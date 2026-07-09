@file:Suppress("ForbiddenImport")
// tag::ktor-main[14] Ktor application entry point

package com.example.viadapp

import io.ktor.server.application.Application

const val SCHEMA_ID: String = "publicSchema"
const val DEFAULT_SCOPE_ID: String = "default"

fun main(argv: Array<String>) {
    io.ktor.server.jetty.jakarta.EngineMain.main(argv)
}

fun Application.module() {
    configurePlugins()
    configureRouting()
    val port = environment.config.propertyOrNull("ktor.deployment.port")?.getString() ?: "8080"
    environment.log.info("GraphiQL available at http://localhost:$port/graphiql")
}
