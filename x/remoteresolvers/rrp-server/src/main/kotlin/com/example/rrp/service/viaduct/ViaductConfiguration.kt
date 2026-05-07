package com.example.rrp.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.config.RemoteResolverConfig
import viaduct.remote.config.RemoteResolverInitializer
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.toSchemaScopeInfo

const val DEFAULT_SCOPE_ID = "default"
const val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA_ID = SchemaId.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA_ID = SchemaId.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))

@Factory
class ViaductConfiguration(
    val tenantModuleBootstrapper: MicronautTenantModuleBootstrapper,
) {
    // Singleton so preDestroy targets the same instance the factory binds to.
    @Singleton
    @Bean(preDestroy = "close")
    fun remoteResolverInitializer(): RemoteResolverInitializer =
        // Force enabled=true; rrp-server always runs with the proxy on.
        RemoteResolverInitializer(RemoteResolverConfig.fromEnvironment().copy(enabled = true))

    @Bean
    fun provideProxyResolverFactory(initializer: RemoteResolverInitializer): ProxyResolverFactory = initializer.initialize()

    @Bean
    fun providesViaduct(proxyResolverFactory: ProxyResolverFactory): Viaduct =
        BasicViaductFactory.createFromResource(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    DEFAULT_SCHEMA_ID.toSchemaScopeInfo(),
                    EXTRAS_SCHEMA_ID.toSchemaScopeInfo(),
                )
            ),
            tenantModuleBootstrapper = tenantModuleBootstrapper,
            proxyResolverFactory = proxyResolverFactory,
        )
}
