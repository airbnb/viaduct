package viaduct.engine.runtime.tenantloading

import viaduct.bootstrap.ViaductResourceIndexes
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.InputStreamSource
import viaduct.utils.resources.ClasspathResourceIndex
import viaduct.utils.resources.requireResourceUrl

private const val REGISTRY_RESOURCE_PATH = "META-INF/viaduct/modules"

object ExecutionRegistryConfigSourceCollector {
    /** Loads indexed module configs as immutable snapshots, rejecting duplicate tenant/API keys. */
    fun fromResources(packagePrefix: String? = null): List<ModuleConfigSource> =
        fromResources(
            packagePrefix,
            Thread.currentThread().contextClassLoader ?: ExecutionRegistryConfigSourceCollector::class.java.classLoader,
        )

    internal fun fromResources(
        packagePrefix: String?,
        classLoader: ClassLoader,
    ): List<ModuleConfigSource> {
        val prefix = "$REGISTRY_RESOURCE_PATH/" + (packagePrefix?.let { "$it." } ?: "")
        val index = requireNotNull(
            ClasspathResourceIndex.load(ViaductResourceIndexes.EXECUTION_REGISTRY, classLoader)
        ) {
            "No Viaduct execution-registry resource index found at '${ViaductResourceIndexes.EXECUTION_REGISTRY}'. " +
                "Apply the Viaduct application Gradle plugin or supply module config sources explicitly."
        }
        return fromResourcePaths(index.paths.filter { it.startsWith(prefix) && it.endsWith(".json") }, classLoader)
    }

    fun fromResourcePaths(paths: Iterable<String>): List<ModuleConfigSource> =
        fromResourcePaths(
            paths,
            Thread.currentThread().contextClassLoader ?: ExecutionRegistryConfigSourceCollector::class.java.classLoader,
        )

    internal fun fromResourcePaths(
        paths: Iterable<String>,
        classLoader: ClassLoader,
    ): List<ModuleConfigSource> {
        val sortedPaths = paths.toList().sorted()
        require(sortedPaths.all(String::isNotBlank)) { "Execution registry resource paths must not be blank" }
        require(sortedPaths.distinct().size == sortedPaths.size) { "Execution registry resource paths must be unique" }

        val sources = sortedPaths.map { path ->
            ModuleConfigSource.from(InputStreamSource.fromUrl(classLoader.requireResourceUrl(path)))
        }
        return ModuleConfigSource.requireUniqueKeys(sources)
    }
}
