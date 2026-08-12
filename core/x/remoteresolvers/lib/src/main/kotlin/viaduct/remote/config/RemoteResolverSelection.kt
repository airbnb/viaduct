package viaduct.remote.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.KOTLIN_API_NAME
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource

/**
 * Exact resolver IDs selected for remote execution by a host application.
 *
 * Empty sets mean no resolvers of that kind are proxied.
 */
data class RemoteResolverSelection(
    val tenantNames: Set<String> = emptySet(),
    val nodeTypes: Set<String> = emptySet(),
    val fieldCoordinates: Set<String> = emptySet(),
) {
    companion object {
        fun fromModuleConfigSources(
            selectedTenantNames: Set<String>,
            moduleConfigSources: List<ModuleConfigSource>,
        ): RemoteResolverSelection {
            if (selectedTenantNames.isEmpty()) {
                return RemoteResolverSelection()
            }

            // A tenant may contribute one config per tenant API implementation, but the remote process
            // only bootstraps the `kotlin` `<pkg>.json` config (see RrsTenantBootstrapper's registry
            // loader). Proxying coordinates from a config the remote side never registers would route
            // those fields to a process that cannot resolve them, so select only the API RRS loads.
            val registrySourcesByTenant = moduleConfigSources
                .filter { it.apiName == PROXYABLE_API_NAME }
                .groupBy { it.tenantName }
            val selectedRegistries =
                selectedTenantNames.map { tenantName ->
                    val sources = registrySourcesByTenant[tenantName]
                        ?: error("No execution registry config found for selected tenant '$tenantName'")
                    val source = sources.singleOrNull()
                        ?: error(
                            "Expected one '$PROXYABLE_API_NAME' execution registry config for selected " +
                                "tenant '$tenantName', found ${sources.size}",
                        )
                    source.source.openStream().use {
                        objectMapper.readValue<ExecutionRegistryConfigFile>(it)
                    }
                }

            // Selective resolvers are not supported by remote execution.
            return RemoteResolverSelection(
                tenantNames = selectedTenantNames,
                nodeTypes =
                    selectedRegistries
                        .flatMap { registry -> registry.nodes.filterNot { it.isSelective }.map { it.typeName } }
                        .toSet(),
                fieldCoordinates =
                    selectedRegistries
                        .flatMap { registry ->
                            registry.fields
                                .filterNot { it.isSelective }
                                .map { "${it.typeName}.${it.fieldName}" }
                        }
                        .toSet(),
            )
        }

        /**
         * The only tenant API whose resolvers can be proxied remotely, because it is the only one the
         * remote process bootstraps. Widening this requires teaching that loader to load the
         * corresponding config resource first.
         */
        private const val PROXYABLE_API_NAME = KOTLIN_API_NAME

        private val objectMapper = jacksonObjectMapper()
    }
}
