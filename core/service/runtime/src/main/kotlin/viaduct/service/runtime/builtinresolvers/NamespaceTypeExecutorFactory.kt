package viaduct.service.runtime.builtinresolvers

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource

/**
 * Built-in [ExecutorFactory] for synthetic `@namespaceType` field resolvers.
 *
 * Instantiated by the file-based bootstrap path via the FQCN recorded in the built-in module
 * config produced by [NamespaceTypeModuleConfigFactory]. The `codeInjector` and `configSource`
 * constructor parameters exist to satisfy the reflective constructor contract shared with tenant
 * executor factories; built-in resolvers need neither.
 *
 * For each field entry, the field's (namespace) return type is resolved from the schema and used to
 * build a [NamespaceTypeFieldResolver], matching the resolver the legacy bootstrapper produced.
 */
class NamespaceTypeExecutorFactory(
    @Suppress("UNUSED_PARAMETER") codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") configSource: InputStreamSource,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor {
        val parent = schema.schema.getObjectType(configData.typeName)
            ?: throw IllegalArgumentException("NamespaceTypeExecutorFactory: parent type '${configData.typeName}' not found in schema")
        val field = parent.getFieldDefinition(configData.fieldName)
            ?: throw IllegalArgumentException(
                "NamespaceTypeExecutorFactory: field '${configData.typeName}.${configData.fieldName}' not found in schema"
            )
        val baseType = GraphQLTypeUtil.unwrapAll(field.type)
        require(baseType is GraphQLObjectType) {
            "NamespaceTypeExecutorFactory: field '${configData.typeName}.${configData.fieldName}' does not return an object type"
        }
        return NamespaceTypeFieldResolver(configData.typeName, configData.fieldName, baseType)
    }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor = throw UnsupportedOperationException("NamespaceTypeExecutorFactory does not create node resolver executors")
}
