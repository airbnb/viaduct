package viaduct.api.bootstrap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import viaduct.api.internal.GRTConvFactory
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.tenant.runtime.bootstrap.ExecutionRegistryBootstrapper
import viaduct.utils.slf4j.logger

/**
 * File-based alternative to [ViaductTenantAPIBootstrapper].
 *
 * Discovers tenant modules by enumerating all resources under [REGISTRY_RESOURCE_PATH]
 * on the classpath — one JSON file per tenant package, each containing a pre-generated
 * [ExecutionRegistry] produced by the build-time KSP + aggregation pipeline (RFC-249).
 *
 * Only instantiated when file-based bootstrapping is explicitly enabled via
 * [ViaductTenantAPIBootstrapper.Builder.useFileBasedBootstrap]. Never created on the
 * default path, so existing startup behavior is completely unaffected.
 */
internal class ExecutionRegistryTenantAPIBootstrapper(
    private val tenantCodeInjector: TenantCodeInjector,
    private val grtPackagePrefix: String,
    private val grtConvFactory: GRTConvFactory,
) : TenantAPIBootstrapper {
    // Keep in sync with ResolverParamsJsonCodec in tenant-codegen (write side).
    private val objectMapper = jacksonObjectMapper()

    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
        val registryUrls = Thread.currentThread().contextClassLoader
            .getResources(REGISTRY_RESOURCE_PATH)
            .toList()

        if (registryUrls.isEmpty()) {
            log.warn("File-based bootstrapping enabled but no registry files found under {}", REGISTRY_RESOURCE_PATH)
        }

        return coroutineScope {
            registryUrls.map { url ->
                async {
                    val registry = url.openStream().use { objectMapper.readValue<ExecutionRegistry>(it) }
                    ExecutionRegistryBootstrapper(
                        registry = registry,
                        tenantCodeInjector = tenantCodeInjector,
                        grtPackagePrefix = grtPackagePrefix,
                        grtConvFactory = grtConvFactory,
                    )
                }
            }.awaitAll()
        }
    }

    companion object {
        private const val REGISTRY_RESOURCE_PATH = "META-INF/viaduct/modules"
        private val log by logger()
    }
}
