package com.example.starwars.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.Viaduct

const val DEFAULT_SCOPE_ID = "default"
const val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA = SchemaScopeInfo.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA = SchemaScopeInfo.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))

// tag::viaduct_configuration[13]
@Factory
class ViaductConfiguration(
    val tenantModuleInjectorFactory: MicronautTenantModuleInjectorFactory,
) {
    @Bean
    fun providesViaduct(): Viaduct =
        // tag::schema_registration[4]
        BasicViaductFactory.create(
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
            scopedSchemas = listOf(DEFAULT_SCHEMA, EXTRAS_SCHEMA),
        )
    // end::schema_registration
}
