package viaduct.graphql.scopes

import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import viaduct.apiannotations.InternalApi
import viaduct.graphql.scopes.utils.getChildrenForElement
import viaduct.graphql.scopes.utils.isIntrospectionType
import viaduct.graphql.scopes.utils.isTenantLocalEquivalentField
import viaduct.utils.memoize.memoize

/**
 * Describes whether an input schema participates in scope-based projection.
 */
sealed interface SchemaScopingMode {
    /** The schema has no declared scope IDs and cannot produce scoped projections. */
    data object Unscoped : SchemaScopingMode

    /** The schema can produce projections for the declared [validScopes]. */
    data class ScopeAware(
        val validScopes: Set<String>,
    ) : SchemaScopingMode {
        init {
            require(validScopes.isNotEmpty()) { "Scope-aware schemas must declare at least one valid scope." }
            require("*" !in validScopes) { "'*' is a wildcard in schema directives, not a valid scope ID." }
        }
    }
}

/**
 * Identifies the semantic schema variant to build.
 */
sealed interface SchemaView {
    /** The complete schema, including tenant-local fields. */
    data object Full : SchemaView

    /** The complete schema with tenant-local fields removed. */
    data object Base : SchemaView

    /** A projection containing fields visible to at least one of [scopes], excluding tenant-local fields. */
    data class Scoped(
        val scopes: Set<String>,
    ) : SchemaView {
        init {
            require(scopes.isNotEmpty()) { "A scoped schema view must select at least one scope." }
            require("*" !in scopes) { "'*' is a schema directive wildcard, not a selectable scope ID." }
        }
    }
}

/**
 * Builds full, base, or scope-filtered views of an input schema.
 *
 * Scope-aware inputs can produce a full view or projections for specific scopes. Unscoped inputs can produce full and
 * base views without participating in scope validation or filtering.
 *
 * See https://viaduct.airbnb.tech/docs/scopes/ for more information.
 *
 * @property inputSchema The input schema from which views are built.
 * @property scopingMode Whether the input schema supports scoped projections and its valid scope IDs.
 * @property additionalVisitorConstructors Additional traverser visitors for transformations. Currently, we default
 *                       to include only the `AddD3Fields` visitor class for the @experimental_dataDrivenDependency
 *                       directive.
 */
class ScopedSchemaBuilder(
    private val inputSchema: GraphQLSchema,
    private val scopingMode: SchemaScopingMode,
    private val additionalVisitorConstructors: List<AdditionalVisitorConstructor>
) {
    /**
     * Builds [view] from the input schema.
     *
     * The resulting schema is not executable; it only contains type metadata, not wiring.
     */
    fun build(view: SchemaView): ScopedGraphQLSchema = build(view, includeTenantLocalFields = false)

    /**
     * Builds the full schema view for the selected scopes, including tenant-local fields.
     *
     * This Airbnb-only API is used by subgraph services for their internal schema. Request schemas
     * should use [SchemaView.Scoped] so tenant-local fields remain hidden from clients.
     */
    @InternalApi
    fun buildScopedFull(scopes: Set<String>): ScopedGraphQLSchema = build(SchemaView.Scoped(scopes), includeTenantLocalFields = true)

    private fun build(
        view: SchemaView,
        includeTenantLocalFields: Boolean,
    ): ScopedGraphQLSchema {
        if (view == SchemaView.Base && !hasTenantLocalFields(inputSchema)) {
            return ScopedGraphQLSchema(inputSchema, inputSchema)
        }
        val scopeTransformer = SchemaScopeTransformer(scopingMode, additionalVisitorConstructors)
        val preparedSchema = replaceAllTypesWithReferences(inputSchema)
        return ScopedGraphQLSchema(
            inputSchema,
            scopeTransformer.transform(preparedSchema, view, includeTenantLocalFields),
        )
    }

    private fun replaceAllTypesWithReferences(inputSchema: GraphQLSchema): GraphQLSchema =
        inputSchema.transform {
            it.query(replaceChildrenWithTypeReferences(inputSchema.queryType) as GraphQLObjectType?)
            it.mutation(replaceChildrenWithTypeReferences(inputSchema.mutationType) as GraphQLObjectType?)
            it.subscription(replaceChildrenWithTypeReferences(inputSchema.subscriptionType) as GraphQLObjectType?)
            val additionalTypes =
                inputSchema.allTypesAsList
                    .filter {
                        !isIntrospectionType(it) &&
                            it != inputSchema.queryType &&
                            it != inputSchema.mutationType &&
                            it != inputSchema.subscriptionType &&
                            it !is GraphQLScalarType
                    }.mapNotNull { type ->
                        replaceChildrenWithTypeReferences(type) as? GraphQLNamedType
                    }.toSet()
            it.clearAdditionalTypes()
            it.additionalTypes(additionalTypes)

            it.clearDirectives()
            it.additionalDirectives(
                inputSchema.directives
                    .map(replaceDirectiveChildrenWithTypeReferences)
                    .toMutableSet()
            )

            it.clearSchemaDirectives()
            it.withSchemaAppliedDirectives(
                inputSchema.schemaAppliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
            )
        }

    private fun replaceChildrenWithTypeReferences(type: GraphQLType?): GraphQLType? =
        when (type) {
            null -> null
            is GraphQLObjectType -> replaceObjectTypeChildrenWithTypeReferences(type)
            is GraphQLInterfaceType -> replaceInterfaceTypeChildrenWithTypeReferences(type)
            is GraphQLInputObjectType -> replaceInputObjectTypeChildrenWithTypeReferences(type)
            is GraphQLUnionType -> replaceUnionTypeChildrenWithTypeReferences(type)
            else -> type
        }

    private fun replaceObjectTypeChildrenWithTypeReferences(type: GraphQLObjectType) =
        type.transform {
            it.replaceFields(
                type.fieldDefinitions.map { fieldDef ->
                    fieldDef.transform {
                        it.type(replaceTypeWithReference(fieldDef.type) as GraphQLOutputType)
                        it.replaceArguments(
                            fieldDef.arguments.map { arg ->
                                arg.transform {
                                    it.type(replaceTypeWithReference(arg.type) as GraphQLInputType)
                                }
                            }
                        )
                        it.replaceDirectives(
                            fieldDef.directives.map(replaceDirectiveChildrenWithTypeReferences)
                        )
                        it.replaceAppliedDirectives(
                            fieldDef.appliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
                        )
                    }
                }
            )
            it.clearInterfaces()
            it.withInterfaces(
                *type.interfaces
                    .map { iface ->
                        replaceTypeWithReference(iface) as GraphQLTypeReference
                    }.toTypedArray()
            )
            it.replaceDirectives(
                type.directives.map(replaceDirectiveChildrenWithTypeReferences)
            )
            it.replaceAppliedDirectives(
                type.appliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
            )
        }

    private fun replaceInterfaceTypeChildrenWithTypeReferences(type: GraphQLInterfaceType) =
        type.transform {
            it.replaceFields(
                type.fieldDefinitions.map { fieldDef ->
                    fieldDef.transform {
                        it.type(replaceTypeWithReference(fieldDef.type) as GraphQLOutputType)
                        it.replaceArguments(
                            fieldDef.arguments.map { arg ->
                                arg.transform {
                                    it.type(replaceTypeWithReference(arg.type) as GraphQLInputType)
                                }
                            }
                        )
                        it.replaceAppliedDirectives(
                            fieldDef.appliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
                        )
                    }
                }
            )
            it.replaceDirectives(
                type.directives.map(replaceDirectiveChildrenWithTypeReferences)
            )
            it.replaceAppliedDirectives(
                type.appliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
            )

            it.replaceInterfaces(listOf())
            type.interfaces
                .map { iface ->
                    replaceTypeWithReference(iface) as GraphQLTypeReference
                }.forEach { ref ->
                    it.withInterface(ref)
                }
        }

    private fun replaceInputObjectTypeChildrenWithTypeReferences(type: GraphQLInputObjectType) =
        type.transform {
            it.replaceFields(
                type.fieldDefinitions.map { fieldDef ->
                    fieldDef.transform {
                        it.type(replaceTypeWithReference(fieldDef.type) as GraphQLInputType)
                        it.replaceDirectives(
                            fieldDef.directives.map(replaceDirectiveChildrenWithTypeReferences)
                        )
                        it.replaceAppliedDirectives(
                            fieldDef.appliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
                        )
                    }
                }
            )
            it.replaceDirectives(
                type.directives.map(replaceDirectiveChildrenWithTypeReferences)
            )
            it.replaceAppliedDirectives(
                type.appliedDirectives.map(replaceAppliedDirectiveChildrenWithTypeReferences)
            )
        }

    private fun replaceUnionTypeChildrenWithTypeReferences(type: GraphQLUnionType) =
        type.transform {
            it.clearPossibleTypes()
            it.possibleTypes(
                *type.types
                    .map { replaceTypeWithReference(it) as GraphQLTypeReference }
                    .toTypedArray()
            )
        }

    @Suppress("FunctionNaming")
    private fun _replaceDirectiveChildrenWithTypeReferences(dir: GraphQLDirective): GraphQLDirective =
        dir.transform {
            it.replaceArguments(
                dir.arguments.map { arg ->
                    arg.transform {
                        it.type(replaceTypeWithReference(arg.type) as GraphQLInputType)
                    }
                }
            )
        }

    private val replaceDirectiveChildrenWithTypeReferences =
        ::_replaceDirectiveChildrenWithTypeReferences.memoize()

    @Suppress("FunctionNaming")
    private fun _replaceAppliedDirectiveChildrenWithTypeReferences(dir: GraphQLAppliedDirective): GraphQLAppliedDirective =
        dir.transform {
            it.replaceArguments(
                dir.arguments.map { arg ->
                    arg.transform {
                        it.type(replaceTypeWithReference(arg.type) as GraphQLInputType)
                    }
                }
            )
        }

    private val replaceAppliedDirectiveChildrenWithTypeReferences =
        ::_replaceAppliedDirectiveChildrenWithTypeReferences.memoize()

    private fun replaceTypeWithReference(type: GraphQLType): GraphQLType =
        when (type) {
            is GraphQLNonNull -> GraphQLNonNull(replaceTypeWithReference(type.wrappedType))
            is GraphQLList -> GraphQLList(replaceTypeWithReference(type.wrappedType))
            is GraphQLScalarType -> type
            is GraphQLNamedSchemaElement -> GraphQLTypeReference.typeRef(type.name)
            else -> error("Can't replace non-named type with type reference.")
        }

    private fun hasTenantLocalFields(schema: GraphQLSchema): Boolean =
        schema.allTypesAsList.any { element ->
            getChildrenForElement(element)?.any { child ->
                isTenantLocalEquivalentField(child)
            } == true
        }
}

data class ScopedGraphQLSchema(
    val original: GraphQLSchema,
    val filtered: GraphQLSchema
)
