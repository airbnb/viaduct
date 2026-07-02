// runBlocking is suppressed (ForbiddenImport): this is a one-time startup bridge to the engine's
// suspend bootstrap API, mirroring the engine's own DispatcherRegistryFactory — not request-path use.
@file:Suppress("ForbiddenImport")

package com.example.remote

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import viaduct.engine.BootstrapperFactory
import viaduct.engine.SchemaFactory
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SchemaRegistry
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

/**
 * Builds node- and field-resolver executors from the tenant-module manifests on the classpath
 * (`META-INF/viaduct/modules/<pkg>.json`) and registers them in [NodeExecutorRegistry] (by type name)
 * and [FieldExecutorRegistry] (by field coordinate) so the remote gRPC service can dispatch resolves.
 * Wiring comes from the manifest entries rather than from parsing SDL, so no full `Viaduct` engine
 * instance is needed just to enumerate resolvers.
 *
 * The schema is loaded from `.graphqls` ([SchemaFactory.fromResources]) and published to
 * [SchemaRegistry] for NETWORK-mode contexts; it also filters which manifest entries are realized.
 */
class TenantBootstrapper(private val tenantCodeInjector: CodeInjector) {
    private val log = LoggerFactory.getLogger(TenantBootstrapper::class.java)

    /** Returns the total number of resolvers registered (nodes + fields). */
    fun bootstrap(): Int {
        log.info("Bootstrapping tenant modules")

        // Schema backs NETWORK-mode remote contexts (via SchemaRegistry) and filters which manifest
        // entries are realized below.
        val schema = SchemaFactory().fromResources()
        SchemaRegistry.register(schema)

        // Build executors straight from the tenant manifests — no Viaduct engine instance needed.
        val (nodeExecutors, fieldExecutors) = runBlocking {
            val bootstrappers = BootstrapperFactory.fromResources(SharedTenantModuleInjectorFactory(tenantCodeInjector))
                .tenantModuleBootstrappers()
                .toList()
            val nodes = bootstrappers.flatMap { it.nodeResolverExecutors(schema) }
            val fields = bootstrappers.flatMap { it.fieldResolverExecutors(schema) }
            nodes to fields
        }

        nodeExecutors.forEach { (typeName, executor) ->
            NodeExecutorRegistry.register(executor)
            log.info("Registered node resolver for type: {}", typeName)
        }

        fieldExecutors.forEach { (_, executor) ->
            FieldExecutorRegistry.register(executor)
            log.info("Registered field resolver for: {}", executor.resolverId)
        }

        log.info(
            "Tenant bootstrap complete; registered {} node resolver(s) and {} field resolver(s)",
            nodeExecutors.size,
            fieldExecutors.size
        )
        return nodeExecutors.size + fieldExecutors.size
    }
}
