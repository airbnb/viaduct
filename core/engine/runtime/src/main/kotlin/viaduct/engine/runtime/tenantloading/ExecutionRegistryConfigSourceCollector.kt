package viaduct.engine.runtime.tenantloading

import io.github.classgraph.ClassGraph
import viaduct.service.api.spi.InputStreamSource

private const val REGISTRY_RESOURCE_PATH = "META-INF/viaduct/modules"

object ExecutionRegistryConfigSourceCollector {
    fun fromResources(packagePrefix: String? = null): List<InputStreamSource> {
        val pattern = if (packagePrefix != null) {
            Regex("$REGISTRY_RESOURCE_PATH/${Regex.escape(packagePrefix)}.*\\.json").toPattern()
        } else {
            Regex("$REGISTRY_RESOURCE_PATH/.*\\.json").toPattern()
        }

        return ClassGraph()
            .acceptPaths(REGISTRY_RESOURCE_PATH)
            .scan()
            .use { result ->
                result.getResourcesMatchingPattern(pattern)
                    .map { InputStreamSource.fromUrl(it.url) }
            }
    }
}
