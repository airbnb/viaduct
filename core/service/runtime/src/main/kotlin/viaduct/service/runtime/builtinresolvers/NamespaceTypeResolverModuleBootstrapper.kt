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
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.graphql.utils.DefaultSchemaFactory

/**
 * Registers synthetic field resolvers for fields returning `@namespaceType` types.
 *
 * Namespace types are used purely for namespacing -- each of their fields have their own
 * resolvers (or return other namespace types). Thus, these resolvers simply create an empty
 * [ResolvedEngineObjectData].
 */
class NamespaceTypeResolverModuleBootstrapper : TenantModuleBootstrapper {
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> =
        buildList {
            val graphQLSchema = schema.schema
            val visited = mutableSetOf<String>()
            walkNamespaceFields(graphQLSchema.queryType, this, visited)
            graphQLSchema.mutationType?.let { walkNamespaceFields(it, this, visited) }
        }

    /**
     * Starting from [parent], register a synthetic resolver for each field whose type
     * has `@namespaceType`, then recurse into that namespace type to find further nested ones.
     * [visited] tracks already-processed type names to guard against cycles.
     */
    private fun walkNamespaceFields(
        parent: GraphQLObjectType,
        result: MutableList<Pair<Coordinate, FieldResolverExecutor>>,
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
                result.add(
                    Coordinate(parent.name, field.name) to
                        NamespaceTypeFieldResolver(parent.name, field.name, baseType)
                )
                if (visited.add(baseType.name)) {
                    walkNamespaceFields(baseType, result, visited)
                }
            }
        }
    }

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> = emptyList()
}

/**
 * A synthetic field resolver that returns an empty [ResolvedEngineObjectData] for a namespace type.
 */
private class NamespaceTypeFieldResolver(
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
