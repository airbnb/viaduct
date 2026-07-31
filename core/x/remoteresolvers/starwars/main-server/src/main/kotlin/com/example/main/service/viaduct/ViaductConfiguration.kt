package com.example.main.service.viaduct

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.runtime.tenantloading.ExecutionRegistryConfigSourceCollector
import viaduct.remote.config.RemoteResolverConfig
import viaduct.remote.config.RemoteResolverInitializer
import viaduct.remote.config.RemoteResolverSelection
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.Viaduct

const val DEFAULT_SCOPE_ID = "default"
const val EXTRAS_SCOPE_ID = "extras"
val DEFAULT_SCHEMA = SchemaScopeInfo.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID))
val EXTRAS_SCHEMA = SchemaScopeInfo.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))

@Factory
class ViaductConfiguration(
    val tenantModuleInjectorFactory: MicronautTenantModuleInjectorFactory,
) {
    // Singleton so preDestroy targets the same instance the factory binds to.
    @Singleton
    @Bean(preDestroy = "close")
    fun remoteResolverInitializer(): RemoteResolverInitializer {
        val moduleConfigSources = ExecutionRegistryConfigSourceCollector.fromResources()

        // The sample always runs with the proxy enabled.
        return RemoteResolverInitializer(
            config = RemoteResolverConfig.fromEnvironment(enabled = true),
            selection =
                RemoteResolverSelection.fromModuleConfigSources(
                    selectedTenantNames = moduleConfigSources.map { it.tenantName }.toSet(),
                    moduleConfigSources = moduleConfigSources,
                ),
        )
    }

    @OptIn(ExperimentalApi::class)
    @Bean
    fun provideProxyResolverFactory(initializer: RemoteResolverInitializer): ProxyResolverFactory = initializer.initialize()

    @OptIn(ExperimentalApi::class)
    @Bean
    fun providesViaduct(proxyResolverFactory: ProxyResolverFactory): Viaduct =
        ViaductBuilder()
            .withScopedSchemas(listOf(DEFAULT_SCHEMA, EXTRAS_SCHEMA))
            .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
            .withProxyResolverFactory(proxyResolverFactory)
            .build()
}
