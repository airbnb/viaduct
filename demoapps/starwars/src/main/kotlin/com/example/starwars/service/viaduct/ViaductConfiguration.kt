package com.example.starwars.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager

const val DEFAULT_SCOPE_ID = "default"
const val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA: SchemaScopeInfo = SchemaScopeInfo.fromResources(setOf("publicSchema")).single()
val EXTRAS_SCHEMA: SchemaScopeInfo = SchemaScopeInfo.fromResources(setOf("publicSchemaWithExtras")).single()
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
            .withScopedSchemas(SchemaScopeInfo.fromResources())
            .withFlagManager(MAT_RESOLUTION_FLAG_MANAGER)
            .build()
    // end::schema_registration
}
