package com.example.viadapp.production

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.Viaduct

val SCHEMA_ID = "default"

/**
 * Micronaut factory that provides the Viaduct instance.
 *
 * This configuration uses dependency injection for the CodeInjector,
 * allowing resolvers to have their dependencies automatically injected.
 *
 * This is used in both production and development modes.
 */
@Factory
class ViaductConfiguration(
    private val micronautCodeInjector: MicronautCodeInjector
) {
    @Bean
    fun providesViaduct(): Viaduct {
        return BasicViaductFactory.createFromResource(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo(SCHEMA_ID)),
            ),
            tenantCodeInjector = micronautCodeInjector,
        )
    }
}
