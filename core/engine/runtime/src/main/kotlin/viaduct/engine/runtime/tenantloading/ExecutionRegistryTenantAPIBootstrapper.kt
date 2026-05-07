package viaduct.engine.runtime.tenantloading

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.TenantModuleBootstrapper
import viaduct.utils.slf4j.logger

/**
 * Engine-owned bootstrapper that creates [LegacyTenantModuleBootstrapper]s from a pre-collected list
 * of registry JSON [URL]s.
 *
 * For each URL, deserializes the [ExecutionRegistry], instantiates the [ExecutorFactory] FQN
 * via the 3-arg constructor (CodeInjector, moduleName, configUrl), and creates executors
 * for each entry in the registry.
 *
 * The framework calls [tenantModuleBootstrapper] once per tenant with the tenant name and the
 * bootstrap class from the registry (or null) to obtain a per-tenant [CodeInjector]. Once all
 * tenants have been bootstrapped, the framework calls [TenantModuleBootstrapper.finalize] before
 * constructing executor factories so stateful implementations can complete cross-tenant setup.
 * Registry reads and executor factory construction are concurrent; bootstrapping is
 * intentionally sequential to keep the [TenantModuleBootstrapper] contract simple.
 *
 * Classpath scanning and URL filtering are the responsibility of the caller — see
 * [viaduct.engine.BootstrapperFactory] in engine/wiring.
 */
class ExecutionRegistryTenantAPIBootstrapper(
    private val registryUrls: List<URL>,
    private val tenantModuleBootstrapper: TenantModuleBootstrapper,
) : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<LegacyTenantModuleBootstrapper> {
        if (registryUrls.isEmpty()) {
            log.warn("No registry files provided to ExecutionRegistryTenantAPIBootstrapper")
        }

        val parsedRegistries = coroutineScope {
            registryUrls.map { url ->
                async {
                    val registry = url.openStream().use { objectMapper.readValue<ExecutionRegistry>(it) }
                    // TODO: add tenantName to ExecutionRegistry itself so we do not recover it from the file name.
                    // https://app.asana.com/1/150975571430/project/1207604899751448/task/1214588083266279?focus=true
                    ParsedRegistry(
                        registry = registry,
                        configUrl = url,
                        tenantName = url.file.substringAfterLast('/').removeSuffix(".json"),
                        bootstrapClass = registry.bootstrapClass?.let { Class.forName(it) },
                    )
                }
            }.awaitAll()
        }

        // Keep bootstrap calls sequential so service-owned TenantModuleBootstrapper implementations
        // do not need to be thread-safe when accumulating cross-tenant state prior to finalize().
        val bootstrappedRegistries = parsedRegistries.map { parsedRegistry ->
            parsedRegistry to tenantModuleBootstrapper.bootstrap(
                tenantName = parsedRegistry.tenantName,
                tenantBootstrapClass = parsedRegistry.bootstrapClass,
            )
        }

        tenantModuleBootstrapper.finalize()

        return coroutineScope {
            bootstrappedRegistries.map { (parsedRegistry, codeInjector) ->
                async {
                    val executorFactory = instantiateExecutorFactory(
                        fqn = parsedRegistry.registry.executorFactory,
                        grtPackagePrefix = parsedRegistry.registry.grtPackagePrefix,
                        configUrl = parsedRegistry.configUrl,
                        codeInjector = codeInjector,
                    )
                    ExecutionRegistryTenantModuleBootstrapper(parsedRegistry.registry, executorFactory)
                }
            }.awaitAll()
        }
    }

    private fun instantiateExecutorFactory(
        fqn: String,
        grtPackagePrefix: String,
        configUrl: URL,
        codeInjector: CodeInjector,
    ): ExecutorFactory {
        val ctor = Class.forName(fqn).getDeclaredConstructor(
            CodeInjector::class.java,
            String::class.java,
            URL::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(codeInjector, grtPackagePrefix, configUrl) as ExecutorFactory
    }

    companion object {
        private val log by logger()
        private val objectMapper = jacksonObjectMapper()
    }
}

private data class ParsedRegistry(
    val registry: ExecutionRegistry,
    val configUrl: URL,
    val tenantName: String,
    val bootstrapClass: Class<*>?,
)
