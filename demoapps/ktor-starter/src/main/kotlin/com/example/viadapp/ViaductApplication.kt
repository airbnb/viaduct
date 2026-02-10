@file:Suppress("ForbiddenImport")
// tag::ktor-main[14] Ktor application entry point

package com.example.viadapp

import io.ktor.server.application.Application

const val SCHEMA_ID: String = "publicSchema"

fun main(argv: Array<String>) {
    io.ktor.server.jetty.EngineMain.main(argv)
}

fun Application.module() {
    configurePlugins()
    configureRouting()
}
