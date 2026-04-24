package viaduct.api.bootstrap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.JarURLConnection
import java.net.URL
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
 * Discovers tenant modules by enumerating all JSON files under [REGISTRY_RESOURCE_PATH]
 * on the classpath — one JSON file per tenant package, each containing a pre-generated
 * [ExecutionRegistry] produced by the build-time KSP + aggregation pipeline.
 *
 * Only instantiated when file-based bootstrapping is explicitly enabled via
 * [ViaductTenantAPIBootstrapper.Builder.useFileBasedBootstrap]. Never created on the
 * default path, so existing startup behavior is completely unaffected.
 */
internal class ExecutionRegistryTenantAPIBootstrapper(
    private val tenantCodeInjector: TenantCodeInjector,
    private val tenantPackagePrefix: String,
    private val grtConvFactory: GRTConvFactory,
) : TenantAPIBootstrapper {
    // Keep in sync with AssembleTenantModuleConfigFile in tenant-codegen (write side).
    private val objectMapper = jacksonObjectMapper()

    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
        val registryUrls = collectRegistryUrls()

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
                        grtPackagePrefix = tenantPackagePrefix,
                        grtConvFactory = grtConvFactory,
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * Returns URLs of `*.json` files under [REGISTRY_RESOURCE_PATH] scoped to [tenantPackagePrefix].
     * Only files whose basename (without `.json`) equals [tenantPackagePrefix] or starts with
     * `"$tenantPackagePrefix."` are returned, preventing accidental loading of registry files from
     * unrelated tenants when multiple tenant packages share the same classpath.
     */
    private fun collectRegistryUrls(): List<URL> {
        val classLoader = Thread.currentThread().contextClassLoader
        return classLoader.getResources(REGISTRY_RESOURCE_PATH).toList()
            .flatMap { dirUrl -> listJsonsInDirectory(classLoader, dirUrl) }
            .filter { url ->
                val pkg = url.path.substringAfterLast('/').removeSuffix(".json")
                pkg == tenantPackagePrefix || pkg.startsWith("$tenantPackagePrefix.")
            }
    }

    private fun listJsonsInDirectory(
        classLoader: ClassLoader,
        dirUrl: URL
    ): List<URL> =
        when (dirUrl.protocol) {
            "file" -> File(dirUrl.toURI())
                .listFiles { f -> f.isFile && f.extension == "json" }
                ?.map { it.toURI().toURL() }
                .orEmpty()

            "jar" -> {
                val jarConn = dirUrl.openConnection() as JarURLConnection
                val entryPrefix = jarConn.entryName.trimEnd('/') + "/"
                // Do NOT close the JarFile — it is owned and cached by the runtime's URL classloader.
                // Calling .use { } here would close the shared JAR and break subsequent classloader reads.
                val entryNames = jarConn.jarFile
                    .entries().toList()
                    .filter { e -> !e.isDirectory && e.name.startsWith(entryPrefix) && e.name.endsWith(".json") }
                    .map { e -> e.name }
                entryNames.mapNotNull { name ->
                    classLoader.getResource(name).also {
                        if (it == null) log.warn("Registry entry '{}' listed in JAR but not resolvable via classloader", name)
                    }
                }
            }

            else -> {
                log.warn("Unsupported URL protocol '{}' for registry directory {}", dirUrl.protocol, dirUrl)
                emptyList()
            }
        }

    companion object {
        private const val REGISTRY_RESOURCE_PATH = "META-INF/viaduct/modules"
        private val log by logger()
    }
}
