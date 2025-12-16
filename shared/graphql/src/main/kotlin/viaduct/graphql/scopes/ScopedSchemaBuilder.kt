package viaduct.graphql.scopes

import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import java.util.SortedSet
import viaduct.graphql.scopes.utils.isIntrospectionType
import viaduct.utils.memoize.memoize

/**
 * Given an input schema, allow the generation of one or more schemas that are projections of the source schema, derived
 * by filtering the schema to certain "scopes".
 *
 * On initialization, this class will optimistically traverse the input schema and gather scope metadata. Upon applying
 * a scope (or scopes), this metadata will be leveraged to do a fast filtering of types and fields from the schema to
 * produce the final schema projection with the scopes applied. The resulting projection will be memoized for the
 * lifetime of the instance of this class.
 *
 * See https://viaduct.airbnb.tech/docs/scopes/ for more information.
 *
 * @property inputSchema The fully-annotated input schema that contains schema elements belonging to
 *                       _all_ available scopes
 * @property validScopes Scopes that are valid to be applied to this schema.
 * @property additionalVisitorClasses Additional transverser visitors for transformations. Currently, we default
 *                       to include only the `AddD3Fields` visitor class for the @experimental_dataDrivenDependency
 *                       directive.
 */
class ScopedSchemaBuilder(
    private val inputSchema: GraphQLSchema,
    private val validScopes: SortedSet<String>,
    private val additionalVisitorConstructors: List<AdditionalVisitorConstructor>
) {
    /**
     * Given a list of "scope" names, return a ScopedGraphQLSchema object, containing the original input schema
     * and the filtered schema with those scopes applied and types/interfaces/enums/unions/fields filtered
     * appropriately.
     *
     * Note that the resulting schema object will not be executable -- it only contains type metadata, not wiring.
     *
     * @property scopesToApply The list of scope names to apply to the input schema.
     * @return a new ScopedGraphQLSchema object containing the original and the filtered GraphQLSchema objects.
     */
    fun applyScopes(scopesToApply: Set<String>): ScopedGraphQLSchema {
        val scopeTransformer = SchemaScopeTransformer(validScopes, additionalVisitorConstructors)
        // replace the types in the schema with TypeReferences
        val preparedSchema = replaceAllTypesWithReferences(inputSchema)
        val scopedSchema =
            scopeTransformer.applyScopes(
                preparedSchema,
                scopesToApply.toSortedSet()
            )
        return ScopedGraphQLSchema(inputSchema, scopedSchema)
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
                    }.map { type ->
                        replaceChildrenWithTypeReferences(type)
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
}

data class ScopedGraphQLSchema(
    val original: GraphQLSchema,
    val filtered: GraphQLSchema
)
