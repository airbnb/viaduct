package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigFactory
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource

/**
 * [ModuleConfigFactory] for the synthetic `@namespaceType` field resolvers.
 *
 * Walks [fullSchema] for every field returning a `@namespaceType` object type (see
 * [namespaceFieldCoordinates]) and generates a module config that routes those fields through
 * [NamespaceTypeExecutorFactory], so the built-in resolvers flow through the same file-based
 * bootstrap path as tenant modules.
 *
 * Returns `null` when the schema has no namespace fields (nothing to contribute).
 */
class NamespaceTypeModuleConfigFactory(
    private val fullSchema: ViaductSchema,
) : ModuleConfigFactory {
    override fun moduleConfigSource(): ModuleConfigSource? {
        val fields = namespaceFieldCoordinates(fullSchema).map { (typeName, fieldName) ->
            builtinFieldEntry(typeName, fieldName, ATTRIBUTION)
        }
        if (fields.isEmpty()) return null

        return buildBuiltinModuleConfigSource(
            tenantName = TENANT_NAME,
            executorFactoryName = NamespaceTypeExecutorFactory::class.java.name,
            fields = fields,
        )
    }

    companion object {
        /** Stable generated source name for this built-in module. */
        const val TENANT_NAME = "viaduct.builtin.namespace_type"
        private const val ATTRIBUTION = "namespace-type-resolver"
    }
}
