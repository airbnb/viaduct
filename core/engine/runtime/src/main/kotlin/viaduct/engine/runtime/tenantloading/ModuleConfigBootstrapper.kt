package viaduct.engine.runtime.tenantloading

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.TenantModuleInjectorFactory

/**
 * Engine-owned orchestration that turns a pre-collected list of [ModuleConfigSource]s into
 * [TenantModuleBootstrapper]s.
 *
 * Resource discovery and tenant-name resolution happen upstream (in
 * [ExecutionRegistryConfigSourceCollector]); this class is concerned only with bootstrap
 * orchestration. For each source, it deserializes the [ExecutionRegistryConfigFile], instantiates
 * the [ExecutorFactory] FQN via the 2-arg constructor (CodeInjector, ExecutionRegistryConfigFile),
 * and creates executors for each entry in the registry.
 *
 * The framework calls [tenantModuleInjectorFactory] once per tenant with the tenant name (taken
 * from the [ModuleConfigSource]) and the bootstrap class from the registry (or null) to obtain a
 * per-tenant [CodeInjector]. Once all tenants have been bootstrapped, the framework calls
 * [TenantModuleInjectorFactory.onBootstrapComplete] before constructing executor factories so
 * stateful implementations can complete cross-tenant setup. Registry reads and executor factory
 * construction are concurrent; bootstrapping is intentionally sequential to keep the
 * [TenantModuleInjectorFactory] contract simple.
 *
 * Pass [grtPackagePrefix] to override the GRT package used by the executor factory, allowing tenant
 * implementations to decouple from the production default (e.g. contract tests generate GRTs into
 * the tenant package rather than the production constant).
 */
class ModuleConfigBootstrapper(
    private val tenantModuleInjectorFactory: TenantModuleInjectorFactory,
    private val grtPackagePrefix: String? = null,
) {
    /**
     * Runs the bootstrap algorithm over [moduleConfigSources] and returns one
     * [TenantModuleBootstrapper] per source.
     */
    suspend fun bootstrap(moduleConfigSources: List<ModuleConfigSource>): List<TenantModuleBootstrapper> {
        val parsedRegistries = coroutineScope {
            moduleConfigSources.map { moduleConfigSource ->
                async {
                    val registry = moduleConfigSource.source.openStream()
                        .use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
                    ParsedRegistry(
                        source = moduleConfigSource,
                        registry = registry,
                        bootstrapClass = registry.bootstrapClass?.let { Class.forName(it) },
                    )
                }
            }.awaitAll()
        }

        // Keep bootstrap calls sequential so service-owned TenantModuleInjectorFactory implementations
        // do not need to be thread-safe when accumulating cross-tenant state prior to onBootstrapComplete().
        val codeInjectorsByTenant = LinkedHashMap<String, CodeInjector>()
        parsedRegistries.groupBy { it.source.tenantName }.forEach { (tenantName, tenantRegistries) ->
            codeInjectorsByTenant[tenantName] = tenantModuleInjectorFactory.bootstrap(
                tenantName = tenantName,
                tenantBootstrapClass = bootstrapClassFor(tenantName, tenantRegistries),
            )
        }

        tenantModuleInjectorFactory.onBootstrapComplete()

        return coroutineScope {
            parsedRegistries.map { parsedRegistry ->
                val codeInjector = codeInjectorsByTenant.getValue(parsedRegistry.source.tenantName)
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

    /**
     * Resolves the single bootstrap class for a tenant from its (possibly multiple) sources. Sources
     * may omit a bootstrap class (null), but any that declare one must agree, since the tenant is
     * bootstrapped exactly once.
     */
    private fun bootstrapClassFor(
        tenantName: String,
        tenantRegistries: List<ParsedRegistry>,
    ): Class<*>? {
        val distinctBootstrapClasses = tenantRegistries.mapNotNull { it.bootstrapClass }.distinct()
        require(distinctBootstrapClasses.size <= 1) {
            "Tenant '$tenantName' declares conflicting bootstrap classes across its config sources: " +
                distinctBootstrapClasses.joinToString { it.name }
        }
        return distinctBootstrapClasses.singleOrNull()
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
        private val objectMapper = jacksonObjectMapper()
    }
}

private data class ParsedRegistry(
    val source: ModuleConfigSource,
    val registry: ExecutionRegistryConfigFile,
    val bootstrapClass: Class<*>?,
)
