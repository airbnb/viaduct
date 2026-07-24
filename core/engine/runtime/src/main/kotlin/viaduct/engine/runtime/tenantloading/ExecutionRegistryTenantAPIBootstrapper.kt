package viaduct.engine.runtime.tenantloading

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.utils.slf4j.logger

/**
 * Engine-owned bootstrapper that creates [TenantModuleBootstrapper]s from a pre-collected list
 * of registry JSON [InputStreamSource]s.
 *
 * For each source, deserializes the [ExecutionRegistryConfigFile], instantiates the [ExecutorFactory] FQN
 * via the 2-arg constructor (CodeInjector, ExecutionRegistryConfigFile), and creates executors
 * for each entry in the registry.
 *
 * The framework calls [tenantModuleInjectorFactory] once per tenant with the tenant name and the
 * bootstrap class from the registry (or null) to obtain a per-tenant [CodeInjector]. Once all
 * tenants have been bootstrapped, the framework calls [TenantModuleInjectorFactory.onBootstrapComplete]
 * before constructing executor factories so stateful implementations can complete cross-tenant setup.
 * Registry reads and executor factory construction are concurrent; bootstrapping is
 * intentionally sequential to keep the [TenantModuleInjectorFactory] contract simple.
 *
 * If [executorRegistryConfigSources] is null, registry resources are discovered from
 * `META-INF/viaduct/modules` on the current classpath for compatibility with the original
 * file-based bootstrap path.
 */
class ExecutionRegistryTenantAPIBootstrapper(
    private val tenantModuleInjectorFactory: TenantModuleInjectorFactory,
    private val executorRegistryConfigSources: List<InputStreamSource>? = null,
    private val grtPackagePrefix: String? = null,
) : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
        val configSources = executorRegistryConfigSources
            ?: ExecutionRegistryConfigSourceCollector.fromResources()

        if (configSources.isEmpty()) {
            log.warn("No registry files provided to ExecutionRegistryTenantAPIBootstrapper")
        }

        val parsedRegistries = coroutineScope {
            configSources.map { source ->
                async {
                    val registry = source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
                    ParsedRegistry(
                        registry = registry,
                        tenantName = registry.tenantName
                            ?: throw IllegalArgumentException("Execution registry config source must include tenantName: $source"),
                        bootstrapClass = registry.bootstrapClass?.let { Class.forName(it) },
                    )
                }
            }.awaitAll()
        }

        // Keep bootstrap calls sequential so service-owned TenantModuleInjectorFactory implementations
        // do not need to be thread-safe when accumulating cross-tenant state prior to onBootstrapComplete().
        val bootstrappedRegistries = parsedRegistries.map { parsedRegistry ->
            parsedRegistry to tenantModuleInjectorFactory.bootstrap(
                tenantName = parsedRegistry.tenantName,
                tenantBootstrapClass = parsedRegistry.bootstrapClass,
            )
        }

        tenantModuleInjectorFactory.onBootstrapComplete()

        return coroutineScope {
            bootstrappedRegistries.map { (parsedRegistry, codeInjector) ->
                async {
                    val executorFactory = instantiateExecutorFactory(
                        fqn = parsedRegistry.registry.executorFactory,
                        registry = parsedRegistry.registry,
                        codeInjector = codeInjector,
                    )
                    ExecutionRegistryTenantModuleBootstrapper(parsedRegistry.registry, executorFactory)
                }
            }.awaitAll()
        }
    }

    private fun instantiateExecutorFactory(
        fqn: String,
        registry: ExecutionRegistryConfigFile,
        codeInjector: CodeInjector,
    ): ExecutorFactory {
        val clazz = Class.forName(fqn)
        @Suppress("UNCHECKED_CAST")
        return if (grtPackagePrefix != null) {
            // Tenant implementations may override the default GRT package prefix so the
            // executor factory is decoupled from any hardcoded constant (e.g. contract tests
            // generate GRTs into the tenant package rather than the production default).
            val ctor = clazz.getDeclaredConstructor(
                CodeInjector::class.java,
                String::class.java,
                ExecutionRegistryConfigFile::class.java,
            )
            ctor.newInstance(codeInjector, grtPackagePrefix, registry) as ExecutorFactory
        } else {
            val ctor = clazz.getDeclaredConstructor(
                CodeInjector::class.java,
                ExecutionRegistryConfigFile::class.java,
            )
            ctor.newInstance(codeInjector, registry) as ExecutorFactory
        }
    }

    companion object {
        private val log by logger()
        private val objectMapper = jacksonObjectMapper()
    }
}

private data class ParsedRegistry(
    val registry: ExecutionRegistryConfigFile,
    val tenantName: String,
    val bootstrapClass: Class<*>?,
)
