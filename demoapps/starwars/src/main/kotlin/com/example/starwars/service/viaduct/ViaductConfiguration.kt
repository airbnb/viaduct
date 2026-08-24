package com.example.starwars.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager

const val DEFAULT_SCOPE_ID = "default"
const val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA = SchemaScopeInfo.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA = SchemaScopeInfo.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))
private val MAT_RESOLUTION_FLAG_MANAGER = object : FlagManager {
    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENABLE_MAT_RESOLUTION
}

// tag::viaduct_configuration[13]
@Factory
class ViaductConfiguration(
    val tenantModuleInjectorFactory: MicronautTenantModuleInjectorFactory,
) {
    @Bean
    fun providesViaduct(): Viaduct =
        // tag::schema_registration[4]
        ViaductBuilder()
            .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
            .withScopedSchemas(listOf(DEFAULT_SCHEMA, EXTRAS_SCHEMA))
            .withFlagManager(MAT_RESOLUTION_FLAG_MANAGER)
            .build()
    // end::schema_registration
}
