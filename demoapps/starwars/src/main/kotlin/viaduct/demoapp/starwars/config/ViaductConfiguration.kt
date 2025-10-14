package viaduct.demoapp.starwars.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.toSchemaScopeInfo

val DEFAULT_SCOPE_ID = "default"
val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA_ID = SchemaId.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA_ID = SchemaId.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))
const val PUBLIC_SCHEMA = "publicSchema"
const val PUBLIC_SCHEMA_WITH_EXTRAS = "publicSchemaWithExtras"

const val DEFAULT_SCOPE = "default"
const val EXTRAS_SCOPE = "extras"

/**
 * Configuration class to set up the Viaduct service with schema and tenant registration.
 *
 * This configuration class is responsible for creating and configuring the Viaduct service,
 * which handles all GraphQL requests in the application. It uses the [BasicViaductFactory]
 * to create a Viaduct instance with the necessary schema and tenant registration information.
 *
 * The [ViaductResolverRegistrar] is imported to ensure that all resolver classes
 * annotated with [viaduct.api.Resolver] are registered as beans in the Spring context.
 */
// tag::viaduct_configuration[37] Viaduct Builder
@Configuration
@Import(ViaductResolverRegistrar::class)
class ViaductConfiguration(
    private val codeInjector: SpringTenantCodeInjector
) {
    @Bean
    fun viaduct(): Viaduct {
        /**
         * The BasicViaductFactory is a utility to create a Viaduct instance with minimal configuration.
         */
        return BasicViaductFactory.create(
            /**
             * StarWars application defines two scoped schemas:
             *
             * 1. PUBLIC_SCHEMA: the base schema with only the default scope
             * 2. PUBLIC_SCHEMA_WITH_EXTRAS: the base schema with the extras header
             */
            // tag::schema_registration[9] Schema registration
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    DEFAULT_SCHEMA_ID.toSchemaScopeInfo(),
                    EXTRAS_SCHEMA_ID.toSchemaScopeInfo(),
                ),
                packagePrefix = "viaduct.demoapp", // Scan the entire viaduct.demoapp package for graphqls resources
                resourcesIncluded = ".*\\.graphqls"
            ),
            /**
             * Tenant registration info is required to let Viaduct discover tenant-specific code such as resolvers.
             *
             * In this configuration, we specify to scan for tenant code in the viaduct.demoapp package.
             */
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "viaduct.demoapp", // Scan the entire viaduct.demoapp package for tenant-specific code
                tenantCodeInjector = codeInjector
            )
        )
    }
}
