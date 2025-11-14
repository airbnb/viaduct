package com.example.starwars.service.viaduct

import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.Viaduct
import viaduct.service.api.ViaductApplication
import viaduct.service.api.ViaductFactory
import viaduct.service.toSchemaScopeInfo

/**
 * ViaductFactory for the StarWars demo application.
 *
 * This factory is used by devserve for development mode.
 * For production, the Micronaut ViaductConfiguration is used instead.
 */
@ViaductApplication
class StarWarsViaductFactory : ViaductFactory {
    override fun createViaduct(): Viaduct {
        return BasicViaductFactory.create(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    DEFAULT_SCHEMA_ID.toSchemaScopeInfo(),
                    EXTRAS_SCHEMA_ID.toSchemaScopeInfo(),
                ),
                packagePrefix = "com.example.starwars",
                resourcesIncluded = ".*\\.graphqls"
            ),
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "com.example.starwars"
            )
        )
    }
}
