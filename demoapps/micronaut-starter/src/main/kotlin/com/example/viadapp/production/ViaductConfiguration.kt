package com.example.viadapp.production

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.Viaduct

val SCHEMA_ID = "publicSchema"
const val DEFAULT_SCOPE_ID = "default"
val DEFAULT_SCHEMA = SchemaScopeInfo.Scoped(SCHEMA_ID, setOf(DEFAULT_SCOPE_ID))

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
