package com.example.starwars.service.viaduct

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

// tag::viaduct_configuration[20]
@Factory
class ViaductConfiguration(
    val tenantModuleBootstrapper: MicronautTenantModuleBootstrapper,
) {
    // @Singleton so the proxy-factory bean below sees the same instance Micronaut
    // calls close() on; without it the bean defaults to prototype scope and the
    // factory binds to a different initializer than the one being closed.
    @Singleton
    @Bean(preDestroy = "close")
    fun remoteResolverInitializer(): RemoteResolverInitializer = RemoteResolverInitializer(RemoteResolverConfig.fromEnvironment())

    @Bean
    fun provideProxyResolverFactory(initializer: RemoteResolverInitializer): ProxyResolverFactory = initializer.initialize()

    @Bean
    fun providesViaduct(proxyResolverFactory: ProxyResolverFactory): Viaduct =
        BasicViaductFactory.createFromResource(
            // tag::schema_registration[11]
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    DEFAULT_SCHEMA_ID.toSchemaScopeInfo(),
                    EXTRAS_SCHEMA_ID.toSchemaScopeInfo(),
                )
            ),
            // end::schema_registration
            tenantModuleBootstrapper = tenantModuleBootstrapper,
            proxyResolverFactory = proxyResolverFactory,
        )
}
