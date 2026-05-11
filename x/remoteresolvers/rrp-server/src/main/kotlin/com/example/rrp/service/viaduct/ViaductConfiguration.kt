package com.example.rrp.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.config.RemoteResolverConfig
import viaduct.remote.config.RemoteResolverInitializer
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.Viaduct

const val DEFAULT_SCOPE_ID = "default"
const val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA = SchemaScopeInfo("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA = SchemaScopeInfo("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))

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

    @OptIn(ExperimentalApi::class)
    @Bean
    fun provideProxyResolverFactory(initializer: RemoteResolverInitializer): ProxyResolverFactory = initializer.initialize()

    @OptIn(ExperimentalApi::class)
    @Bean
    fun providesViaduct(proxyResolverFactory: ProxyResolverFactory): Viaduct =
        ViaductBuilder()
            .withScopedSchemas(listOf(DEFAULT_SCHEMA, EXTRAS_SCHEMA))
            .withTenantModuleBootstrapper(tenantModuleBootstrapper)
            .withProxyResolverFactory(proxyResolverFactory)
            .build()
}
