package viaduct.graphql.scopes

import graphql.Directives
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.SchemaTransformer
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser
import viaduct.graphql.scopes.utils.getChildrenForElement
import viaduct.graphql.scopes.visitors.CompositeVisitor
import viaduct.graphql.scopes.visitors.FilterChildrenVisitor
import viaduct.graphql.scopes.visitors.FilterTenantLocalFieldsVisitor
import viaduct.graphql.scopes.visitors.SchemaTransformations
import viaduct.graphql.scopes.visitors.TransformationsVisitor
import viaduct.graphql.scopes.visitors.TypeRemovalVisitor
import viaduct.graphql.scopes.visitors.ValidateRequiredScopesVisitor
import viaduct.graphql.scopes.visitors.ValidateScopesVisitor

typealias AdditionalVisitorConstructor = (
    GraphQLSchema,
    MutableSet<String>,
    MutableMap<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>,
    Set<String>
) -> TraverserVisitorStub<GraphQLSchemaElement>

internal class SchemaScopeTransformer(
    private val scopingMode: SchemaScopingMode,
    private val additionalVisitorConstructors: List<AdditionalVisitorConstructor>
) {
    fun transform(
        inputSchema: GraphQLSchema,
        view: SchemaView,
    ): GraphQLSchema {
        val schemaTransformations = buildTransformations(inputSchema, view)
        return transformAndNormalizeDirectives(inputSchema, schemaTransformations)
    }

    private fun transformAndNormalizeDirectives(
        inputSchema: GraphQLSchema,
        schemaTransformations: SchemaTransformations,
    ): GraphQLSchema {
        return transformSchema(inputSchema, schemaTransformations).let { scopedSchema ->
            // NOTE(jimmy): There is a known issue where graphql-java can duplicate the skip+include directives
            // when transforming a schema that already has skip+include in its input. The issue is fixed when
            // constructing a schema via (un)ExecutableSchemaBuilder, but not when using GraphQLSchema.newSchema(),
            // which transformations do when the schema has changed.
            scopedSchema.transform {
                val skipAndInclude = setOf(Directives.IncludeDirective, Directives.SkipDirective)
                it.clearDirectives()
                it.additionalDirectives(
                    scopedSchema.directives.filter { it.name !in skipAndInclude.map { it.name } }.toSet() +
                        skipAndInclude
                )
            }
        }
    }

    private fun buildTransformations(
        schema: GraphQLSchema,
        view: SchemaView,
    ): SchemaTransformations {
        val stubRoot = StubRoot(schema)
        val elementChildren =
            schema.allTypesAsList
                .associate {
                    Pair(it as GraphQLSchemaElement, getChildrenForElement(it))
                }.toMutableMap()
        val typesToRemove = mutableSetOf<String>()
        val appliedScopes = appliedScopes(view)
        val additionalVisitors =
            additionalVisitorConstructors
                .map { it(schema, typesToRemove, elementChildren, appliedScopes) }
                .toTypedArray()

        val visitor = when (view) {
            SchemaView.Full -> when (scopingMode) {
                SchemaScopingMode.Unscoped -> CompositeVisitor(*additionalVisitors)
                is SchemaScopingMode.ScopeAware -> CompositeVisitor(
                    ValidateRequiredScopesVisitor(ScopeDirectiveParser(scopingMode.validScopes)),
                    *additionalVisitors,
                )
            }

            SchemaView.Base -> CompositeVisitor(
                FilterTenantLocalFieldsVisitor(elementChildren),
                TypeRemovalVisitor(typesToRemove, elementChildren),
                *additionalVisitors,
            )

            is SchemaView.Scoped -> {
                val scopeAware = requireScopeAware(view)
                val scopeDirectiveParser = ScopeDirectiveParser(scopeAware.validScopes)
                CompositeVisitor(
                    ValidateScopesVisitor(scopeAware.validScopes, scopeDirectiveParser),
                    FilterChildrenVisitor(
                        appliedScopes = appliedScopes,
                        scopeDirectiveParser = scopeDirectiveParser,
                        elementChildren = elementChildren,
                    ),
                    TypeRemovalVisitor(typesToRemove, elementChildren),
                    *additionalVisitors,
                )
            }
        }

        buildSchemaTraverser(schema).traverse(stubRoot, visitor)

        return SchemaTransformations(
            elementChildren = elementChildren,
            typesNamesToRemove = typesToRemove
        )
    }

    private fun appliedScopes(view: SchemaView): Set<String> =
        when (view) {
            SchemaView.Base -> emptySet()
            SchemaView.Full -> when (scopingMode) {
                SchemaScopingMode.Unscoped -> emptySet()
                is SchemaScopingMode.ScopeAware -> scopingMode.validScopes
            }
            is SchemaView.Scoped -> view.scopes
        }

    private fun requireScopeAware(view: SchemaView.Scoped): SchemaScopingMode.ScopeAware {
        val scopeAware = scopingMode as? SchemaScopingMode.ScopeAware
            ?: throw IllegalArgumentException("Cannot build a scoped schema view from an unscoped schema.")
        val unknownScopes = view.scopes - scopeAware.validScopes
        require(unknownScopes.isEmpty()) {
            "Scoped schema view contains unknown scopes: ${unknownScopes.sorted()}."
        }
        return scopeAware
    }

    /**
     * Given a schema and a set of transformations, transform the input schema.
     */
    private fun transformSchema(
        schema: GraphQLSchema,
        transformations: SchemaTransformations
    ): GraphQLSchema = SchemaTransformer.transformSchema(schema, TransformationsVisitor(transformations))
}
