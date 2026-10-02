// runBlocking is suppressed (ForbiddenImport): this is a one-time startup bridge to the engine's
// suspend bootstrap API, mirroring the engine's own DispatcherRegistryFactory — not request-path use.
@file:Suppress("ForbiddenImport")

package com.example.remote

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import viaduct.engine.SchemaFactory
import viaduct.engine.runtime.tenantloading.ExecutionRegistryConfigSourceCollector
import viaduct.engine.runtime.tenantloading.ModuleConfigBootstrapper
import viaduct.remote.RemoteResolverRuntime
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import viaduct.service.runtime.builtinresolvers.builtinModuleConfigSources

/**
 * Builds the schema and resolver executors used by the remote gRPC service from the tenant-module
 * manifests on the classpath (`META-INF/viaduct/modules/<pkg>.json`).
 */
class TenantBootstrapper(private val tenantCodeInjector: CodeInjector) {
    private val log = LoggerFactory.getLogger(TenantBootstrapper::class.java)

    fun bootstrap(): RemoteResolverRuntime {
        log.info("Bootstrapping tenant modules")

        // Schema backs schema-only remote contexts and filters which manifest entries are realized.
        val schema = SchemaFactory().fromResources()
        // Build executors straight from the tenant manifests — no Viaduct engine instance needed.
        val (nodeExecutors, fieldExecutors) = runBlocking {
            // Register both the tenant manifests and the engine's built-in resolvers (Query.node /
            // Query.nodes and @namespaceType field resolvers). The built-ins aren't in the tenant
            // manifests — the engine synthesizes them as generated module config sources at startup —
            // so proxying e.g. Query.node needs them registered here too.
            val configSources = ExecutionRegistryConfigSourceCollector.fromResources() +
                builtinModuleConfigSources(schema, defaultQueryNodeResolversEnabled = true)
            val allModules = ModuleConfigBootstrapper(
                SharedTenantModuleInjectorFactory(tenantCodeInjector),
            ).bootstrap(configSources)
            val nodes = allModules.flatMap { it.nodeResolverExecutors(schema) }
            val fields = allModules.flatMap { it.fieldResolverExecutors(schema) }
            nodes to fields
        }

        val runtime = RemoteResolverRuntime(
            schema,
            nodeExecutors.map { it.second },
            fieldExecutors.map { it.second },
        )

        log.info(
            "Tenant bootstrap complete; built {} node resolver(s) and {} field resolver(s)",
            runtime.nodeExecutors.size,
            runtime.fieldExecutors.size
        )
        return runtime
    }
}
