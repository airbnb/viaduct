package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.spi.TenantModuleException

internal fun validateSchemaFree(registry: ExecutionRegistry) {
    validateFields(registry.fields)
    validateNodes(registry.nodes)
}

internal fun validateFields(fields: List<FieldEntry>) =
    // TenantModuleException is semantically correct here; ViaductTenantModuleBootstrapper
    // throws RuntimeException for the same violation (historical inconsistency).
    checkDuplicates(fields, { it.typeName to it.fieldName }) { extant, entry ->
        "Duplicate resolver for type ${entry.typeName} and field ${entry.fieldName}: " +
            "${extant.tenantAPIData.resolverClass} and ${entry.tenantAPIData.resolverClass} both define this field."
    }

internal fun validateNodes(nodes: List<NodeEntry>) =
    checkDuplicates(nodes, { it.typeName }) { extant, entry ->
        "Duplicate node resolver for type ${entry.typeName}: " +
            "${extant.tenantAPIData.resolverClass} and ${entry.tenantAPIData.resolverClass} both define this node."
    }

private fun <K, T> checkDuplicates(
    items: List<T>,
    key: (T) -> K,
    message: (T, T) -> String
) {
    val seen = mutableMapOf<K, T>()
    for (item in items) {
        seen.put(key(item), item)?.let { extant -> throw TenantModuleException(message(extant, item)) }
    }
}
