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
 * Uses [MicronautTenantModuleBootstrapper] so Viaduct resolves tenant classes through the
 * application's shared Micronaut [io.micronaut.context.BeanContext].
 */
@Factory
class ViaductConfiguration(
    private val tenantModuleBootstrapper: MicronautTenantModuleBootstrapper,
) {
    @Bean
    fun providesViaduct(): Viaduct {
        return BasicViaductFactory.createFromResource(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo(SCHEMA_ID)),
            ),
            tenantModuleBootstrapper = tenantModuleBootstrapper,
        )
    }
}
