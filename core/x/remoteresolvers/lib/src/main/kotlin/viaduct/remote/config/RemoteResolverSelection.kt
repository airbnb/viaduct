package viaduct.remote.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
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

            val registrySourcesByTenant = moduleConfigSources.associateBy { it.tenantName }
            val selectedRegistries =
                selectedTenantNames.map { tenantName ->
                    val source =
                        registrySourcesByTenant[tenantName]
                            ?: error("No execution registry config found for selected tenant '$tenantName'")
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

        private val objectMapper = jacksonObjectMapper()
    }
}
