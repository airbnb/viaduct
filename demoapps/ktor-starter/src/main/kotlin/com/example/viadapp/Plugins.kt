package com.example.viadapp

import com.example.viadapp.di.viaductModule
import com.example.viadapp.resolvers.di.resolversModule
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configurePlugins() {
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }
    }

    if (pluginOrNull(Koin) == null) {
        install(Koin) {
            slf4jLogger()
            // resolversModule: services and resolver factories
            // viaductModule: Viaduct singleton that uses Koin for DI
            modules(resolversModule, viaductModule)
        }
    }
}
