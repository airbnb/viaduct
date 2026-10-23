package com.example.starwars.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager

private val MAT_RESOLUTION_FLAG_MANAGER = object : FlagManager {
    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENABLE_MAT_RESOLUTION
}

// tag::viaduct_configuration
class StarWarsSchemas(
    val default: SchemaScopeInfo,
    val extras: SchemaScopeInfo,
)

@Factory
class ViaductConfiguration(
    val tenantModuleInjectorFactory: MicronautTenantModuleInjectorFactory,
) {
    // tag::declared_schemas
    @Singleton
    fun providesStarWarsSchemas(): StarWarsSchemas =
        StarWarsSchemas(
            default = SchemaScopeInfo.fromResources(setOf("publicSchema")).single(),
            extras = SchemaScopeInfo.fromResources(setOf("publicSchemaWithExtras")).single(),
        )
    // end::declared_schemas

    @Bean
    fun providesViaduct(): Viaduct =
        // tag::schema_registration
        ViaductBuilder()
            .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
            .withScopedSchemas(SchemaScopeInfo.fromResources())
            .withFlagManager(MAT_RESOLUTION_FLAG_MANAGER)
            .build()
    // end::schema_registration
}
// end::viaduct_configuration
