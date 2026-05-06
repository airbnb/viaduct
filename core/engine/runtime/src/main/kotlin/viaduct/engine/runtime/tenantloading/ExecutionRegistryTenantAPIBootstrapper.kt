@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")

package viaduct.engine.runtime.tenantloading

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.service.api.spi.CodeInjector
import viaduct.utils.slf4j.logger

/**
 * Engine-owned bootstrapper that creates [LegacyTenantModuleBootstrapper]s from a pre-collected list
 * of registry JSON [URL]s.
 *
 * For each URL, deserializes the [ExecutionRegistry], instantiates the [ExecutorFactory] FQN
 * via the 3-arg constructor (CodeInjector, moduleName, configUrl), and creates executors
 * for each entry in the registry.
 *
 * Classpath scanning and URL filtering are the responsibility of the caller — see
 * [viaduct.engine.BootstrapperFactory] in engine/wiring.
 */
class ExecutionRegistryTenantAPIBootstrapper(
    private val tenantCodeInjector: CodeInjector,
    private val registryUrls: List<URL>,
) : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<LegacyTenantModuleBootstrapper> {
        if (registryUrls.isEmpty()) {
            log.warn("No registry files provided to ExecutionRegistryTenantAPIBootstrapper")
        }

        return coroutineScope {
            registryUrls.map { url ->
                async {
                    val registry = url.openStream().use { objectMapper.readValue<ExecutionRegistry>(it) }
                    val executorFactory = instantiateExecutorFactory(registry.executorFactory, registry.grtPackagePrefix, url)
                    ExecutionRegistryTenantModuleBootstrapper(registry, executorFactory)
                }
            }.awaitAll()
        }
    }

    private fun instantiateExecutorFactory(
        fqn: String,
        grtPackagePrefix: String,
        configUrl: URL
    ): ExecutorFactory {
        val ctor = Class.forName(fqn).getDeclaredConstructor(
            CodeInjector::class.java,
            String::class.java,
            URL::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(tenantCodeInjector, grtPackagePrefix, configUrl) as ExecutorFactory
    }

    companion object {
        private val log by logger()
        private val objectMapper = jacksonObjectMapper()
    }
}

private class ExecutionRegistryTenantModuleBootstrapper(
    private val registry: ExecutionRegistry,
    private val executorFactory: ExecutorFactory,
) : LegacyTenantModuleBootstrapper {
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Pair<String, String>, FieldResolverExecutor>> =
        registry.fields.map { entry ->
            (entry.typeName to entry.fieldName) to executorFactory.createFieldResolverExecutor(entry, schema)
        }

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> =
        registry.nodes.map { entry ->
            entry.typeName to executorFactory.createNodeResolverExecutor(entry, schema)
        }
}
