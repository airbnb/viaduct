package com.example.viadapp.production

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.Viaduct

val SCHEMA_ID = "publicSchema"
val DEFAULT_SCHEMA = SchemaScopeInfo.fromResources(setOf(SCHEMA_ID)).single()

/**
 * Micronaut factory that provides the Viaduct instance.
 *
 * Uses [MicronautTenantModuleInjectorFactory] so Viaduct resolves tenant classes through the
 * application's shared Micronaut [io.micronaut.context.BeanContext].
 */
@Factory
class ViaductConfiguration(
    private val tenantModuleInjectorFactory: MicronautTenantModuleInjectorFactory,
) {
    @Bean
    fun providesViaduct(): Viaduct {
        return BasicViaductFactory.create(
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
            scopedSchemas = listOf(DEFAULT_SCHEMA),
        )
    }
}
