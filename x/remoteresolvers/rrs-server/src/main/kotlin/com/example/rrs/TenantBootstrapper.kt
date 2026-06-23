// runBlocking is suppressed (ForbiddenImport): this is a one-time startup bridge to the engine's
// suspend bootstrap API, mirroring the engine's own DispatcherRegistryFactory — not request-path use.
@file:Suppress("ForbiddenImport")

package com.example.rrs

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import viaduct.engine.BootstrapperFactory
import viaduct.engine.SchemaFactory
import viaduct.remote.registry.ExecutorRegistry
import viaduct.remote.registry.SchemaRegistry
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

/**
 * Builds node-resolver executors from the tenant-module manifests on the classpath
 * (`META-INF/viaduct/modules/<pkg>.json`) and registers them in [ExecutorRegistry], so the RRS
 * gRPC service can dispatch resolves by type name. This is the RFC-249 file-based bootstrap pattern:
 * executor wiring comes from the manifest entries, not from parsing SDL — so no full `Viaduct`
 * engine instance is needed just to enumerate resolvers.
 *
 * The schema is loaded from `.graphqls` ([SchemaFactory.fromResources]) and published to
 * [SchemaRegistry] for NETWORK-mode contexts; it also filters which manifest entries are realized.
 */
class TenantBootstrapper(private val tenantCodeInjector: CodeInjector) {
    private val log = LoggerFactory.getLogger(TenantBootstrapper::class.java)

    /** Returns the number of node resolvers registered. */
    fun bootstrap(): Int {
        log.info("Bootstrapping tenant modules")

        // Schema backs NETWORK-mode remote contexts (via SchemaRegistry) and filters which manifest
        // entries are realized below.
        val schema = SchemaFactory().fromResources()
        SchemaRegistry.register(schema)

        // Build node executors straight from the tenant manifests — no Viaduct engine instance needed.
        val nodeExecutors = runBlocking {
            BootstrapperFactory.fromResources(SharedTenantModuleInjectorFactory(tenantCodeInjector))
                .tenantModuleBootstrappers()
                .flatMap { it.nodeResolverExecutors(schema) }
        }

        nodeExecutors.forEach { (typeName, executor) ->
            ExecutorRegistry.register(executor)
            log.info("Registered node resolver for type: {}", typeName)
        }

        log.info("Tenant bootstrap complete; registered {} node resolver(s)", nodeExecutors.size)
        return nodeExecutors.size
    }
}
