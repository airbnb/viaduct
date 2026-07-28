package viaduct.service.runtime.builtinresolvers

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.service.api.spi.CodeInjector

/**
 * Built-in [ExecutorFactory] for synthetic `@namespaceType` field resolvers.
 *
 * Instantiated by the file-based bootstrap path via the FQCN recorded in the built-in module
 * config produced by [NamespaceTypeModuleConfigFactory]. The `codeInjector` and `registry`
 * constructor parameters exist to satisfy the reflective constructor contract shared with tenant
 * executor factories; built-in resolvers need neither.
 *
 * For each field entry, the field's (namespace) return type is resolved from the schema and used to
 * build a [NamespaceTypeFieldResolver].
 */
class NamespaceTypeExecutorFactory(
    @Suppress("UNUSED_PARAMETER") codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    /**
     * GRT-prefix constructor — required so the bootstrap path can instantiate this factory when a
     * `grtPackagePrefix` override is in effect. Built-in resolvers are schema-independent and do no
     * GRT reflection, so the prefix is ignored.
     */
    constructor(
        codeInjector: CodeInjector,
        @Suppress("UNUSED_PARAMETER") grtPackagePrefix: String,
        registry: ExecutionRegistryConfigFile,
    ) : this(codeInjector, registry)

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

/**
 * Enumerates the coordinates of all fields returning a `@namespaceType` object type, reachable from
 * the Query and Mutation roots.
 *
 * This is the single source of truth for namespace-field discovery, consumed by
 * [NamespaceTypeModuleConfigFactory] to generate the built-in module config.
 */
internal fun namespaceFieldCoordinates(schema: ViaductSchema): List<Coordinate> =
    buildList {
        val graphQLSchema = schema.schema
        val visited = mutableSetOf<String>()
        walkNamespaceFields(graphQLSchema.queryType, this, visited)
        graphQLSchema.mutationType?.let { walkNamespaceFields(it, this, visited) }
    }

/**
 * Starting from [parent], record the coordinate of each field whose type has `@namespaceType`, then
 * recurse into that namespace type to find further nested ones. [visited] tracks already-processed
 * type names to guard against cycles.
 */
private fun walkNamespaceFields(
    parent: GraphQLObjectType,
    result: MutableList<Coordinate>,
    visited: MutableSet<String>
) {
    for (field in parent.fieldDefinitions) {
        val baseType = GraphQLTypeUtil.unwrapAll(field.type)
        if (
            baseType is GraphQLObjectType &&
            baseType.hasAppliedDirective(DefaultSchemaFactory.DefaultDirective.NAMESPACE_TYPE.directiveName)
        ) {
            check(!GraphQLTypeUtil.isWrapped(field.type)) {
                "Field '${parent.name}.${field.name}' has wrapped namespace type ${baseType.name}"
            }
            result.add(Coordinate(parent.name, field.name))
            if (visited.add(baseType.name)) {
                walkNamespaceFields(baseType, result, visited)
            }
        }
    }
}

/**
 * A synthetic field resolver that returns an empty [ResolvedEngineObjectData] for a namespace type.
 */
internal class NamespaceTypeFieldResolver(
    parentTypeName: String,
    fieldName: String,
    private val namespaceType: GraphQLObjectType
) : FieldResolverExecutor {
    override val objectSelectionSet: RequiredSelectionSet? = null
    override val querySelectionSet: RequiredSelectionSet? = null
    override val isSelective: Boolean = false
    override val resolverId: String = "$parentTypeName.$fieldName"
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(
        "namespace-type-resolver",
        ResolverType.FIELD
    )
    override val isBatching: Boolean = false

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got ${selectors.size}" }
        val selector = selectors.first()
        return mapOf(
            selector to Result.success(ResolvedEngineObjectData(namespaceType, emptyMap()))
        )
    }
}
