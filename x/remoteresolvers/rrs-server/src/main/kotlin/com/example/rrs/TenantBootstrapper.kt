package com.example.rrs

import org.slf4j.LoggerFactory
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.SchemaFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.registry.ExecutorRegistry
import viaduct.remote.registry.SchemaRegistry
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

/**
 * Bootstraps tenant modules locally and registers every discovered node-resolver executor
 * in [ExecutorRegistry] so the RRS gRPC service can dispatch by type name.
 */
class TenantBootstrapper(private val tenantCodeInjector: CodeInjector) {
    private val log = LoggerFactory.getLogger(TenantBootstrapper::class.java)
    private var registeredCount = 0

    private inner class RegistrationProxyFactory : ProxyResolverFactory {
        override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor? {
            ExecutorRegistry.register(executor)
            log.info("Registered node resolver for type: {}", executor.typeName)
            registeredCount++
            return null
        }

        override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor? = null
    }

    /** Returns the number of node resolvers registered. */
    @OptIn(ExperimentalApi::class)
    fun bootstrap(): Int {
        log.info("Bootstrapping tenant modules")
        registeredCount = 0

        // Building Viaduct wraps every tenant resolver through the proxy factory at
        // bootstrap time; RegistrationProxyFactory uses that pass to enumerate and
        // register the node resolvers. The built instance itself isn't needed here.
        ViaductBuilder()
            .withScopedSchemas(listOf(DEFAULT_SCHEMA, EXTRAS_SCHEMA))
            .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(tenantCodeInjector))
            .withProxyResolverFactory(RegistrationProxyFactory())
            .build()

        // Network-mode contexts have no delegate; publish the schema for them to read.
        SchemaRegistry.register(SchemaFactory().fromResources())

        log.info("Tenant bootstrap complete; registered {} node resolver(s)", registeredCount)
        return registeredCount
    }

    private companion object {
        const val DEFAULT_SCOPE_ID = "default"
        const val EXTRAS_SCOPE_ID = "extras"
        val DEFAULT_SCHEMA = SchemaScopeInfo("publicSchema", setOf(DEFAULT_SCOPE_ID))
        val EXTRAS_SCHEMA = SchemaScopeInfo("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID))
    }
}
