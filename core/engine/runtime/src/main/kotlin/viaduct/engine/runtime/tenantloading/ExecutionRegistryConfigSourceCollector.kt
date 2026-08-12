package viaduct.engine.runtime.tenantloading

import io.github.classgraph.ClassGraph
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.InputStreamSource

private const val REGISTRY_RESOURCE_PATH = "META-INF/viaduct/modules"

object ExecutionRegistryConfigSourceCollector {
    /**
     * Discovers tenant module registry JSON resources under [REGISTRY_RESOURCE_PATH] on the current
     * classpath and returns one [ModuleConfigSource] per matching resource.
     *
     * Each resource is parsed just enough to extract its [ExecutionRegistryConfigFile.tenantName]
     * and [ExecutionRegistryConfigFile.apiName], which together form the source's configuration key.
     * Discovery deliberately does not retain the fully-parsed config; the bootstrapper re-opens
     * [ModuleConfigSource.source] to read the rest. This keeps resource discovery separate from
     * bootstrap orchestration.
     *
     * Results are required to be uniquely keyed by `<tenantName, apiName>`: this is the earliest
     * practical boundary for the one-config-per-key build invariant. Two classpath resources
     * claiming the same key (e.g. a duplicated or shadowed tenant jar) are a malformed build input
     * and fail here rather than having the collision resolved by classpath scan order.
     *
     * @throws IllegalArgumentException if a discovered registry file has no `tenantName` or no
     *   non-blank `apiName`, or if two discovered files share a `<tenantName, apiName>` key.
     */
    fun fromResources(packagePrefix: String? = null): List<ModuleConfigSource> {
        val pattern = if (packagePrefix != null) {
            Regex("$REGISTRY_RESOURCE_PATH/${Regex.escape(packagePrefix)}.*\\.json").toPattern()
        } else {
            Regex("$REGISTRY_RESOURCE_PATH/.*\\.json").toPattern()
        }

        val sources = ClassGraph()
            .acceptPaths(REGISTRY_RESOURCE_PATH)
            .scan()
            .use { result ->
                result.getResourcesMatchingPattern(pattern)
                    .map { resource -> ModuleConfigSource.from(InputStreamSource.fromUrl(resource.url)) }
            }
        return ModuleConfigSource.requireUniqueKeys(sources)
    }
}
