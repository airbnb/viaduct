package viaduct.graphql.scopes

import graphql.Directives
import graphql.schema.GraphQLFieldDefinition
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
import viaduct.graphql.utils.DefaultSchemaFactory

typealias AdditionalVisitorConstructor = (
    GraphQLSchema,
    MutableSet<String>,
    MutableMap<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>,
    Set<String>
) -> TraverserVisitorStub<GraphQLSchemaElement>

internal class SchemaScopeTransformer(
    private val validScopes: Set<String>,
    private val additionalVisitorConstructors: List<AdditionalVisitorConstructor>
) {
    private companion object {
        val TENANT_LOCAL_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.TENANT_LOCAL.directiveName
    }

    fun applyScopes(
        inputSchema: GraphQLSchema,
        appliedScopes: Set<String>,
        includeTenantLocalFields: Boolean,
    ): GraphQLSchema {
        val schemaTransformations = buildTransformations(
            inputSchema,
            appliedScopes,
            includeTenantLocalFields = includeTenantLocalFields,
        )
        return transformAndNormalizeDirectives(inputSchema, schemaTransformations)
    }

    fun applyBaseSchema(inputSchema: GraphQLSchema): GraphQLSchema {
        if (!hasTenantLocalFields(inputSchema)) {
            return inputSchema
        }
        val schemaTransformations = buildBaseSchemaTransformations(inputSchema)
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

    fun buildTransformations(
        schema: GraphQLSchema,
        appliedScopes: Set<String>,
        includeTenantLocalFields: Boolean = false,
    ): SchemaTransformations {
        // Traverse the Schema AST
        val stubRoot = StubRoot(schema)
        val scopeDirectiveParser = ScopeDirectiveParser(validScopes)

        // Build a mutable map of element -> children, where we will store filtered children
        val elementChildren =
            schema.allTypesAsList
                .associate {
                    Pair(it as GraphQLSchemaElement, getChildrenForElement(it))
                }.toMutableMap()

        // Keep track of types we want to remove from the schema
        val typesToRemove = mutableSetOf<String>()

        val additionalVisitors =
            additionalVisitorConstructors
                .map { it(schema, typesToRemove, elementChildren, appliedScopes) }
                .toTypedArray()
        val visitor = when {
            validScopes.isEmpty() && appliedScopes.isEmpty() && includeTenantLocalFields ->
                // If no scopes are applied, we can skip the validation and just run additional visitors
                CompositeVisitor(
                    *additionalVisitors
                )

            appliedScopes == validScopes && includeTenantLocalFields -> CompositeVisitor(
                ValidateRequiredScopesVisitor(scopeDirectiveParser),
                *additionalVisitors
            )

            else -> {
                // Build a composite visitor that will run child visitors (FilterChildren and TypeRemoval, in this case)
                CompositeVisitor(
                    ValidateScopesVisitor(validScopes, scopeDirectiveParser),
                    // inspect the scope information for this type or its extensions and save it's modified children
                    // in `elementChildren`
                    FilterChildrenVisitor(
                        appliedScopes = appliedScopes,
                        scopeDirectiveParser = scopeDirectiveParser,
                        elementChildren = elementChildren,
                    ),
                    // Based on the filtered children, decide if the element should be removed
                    TypeRemovalVisitor(typesToRemove, elementChildren),
                    // Run any additional visitors
                    *additionalVisitors
                )
            }
        }

        buildSchemaTraverser(schema).traverse(stubRoot, visitor)

        return SchemaTransformations(
            elementChildren = elementChildren,
            typesNamesToRemove = typesToRemove
        )
    }

    private fun buildBaseSchemaTransformations(schema: GraphQLSchema): SchemaTransformations {
        val stubRoot = StubRoot(schema)
        val elementChildren =
            schema.allTypesAsList
                .associate {
                    Pair(it as GraphQLSchemaElement, getChildrenForElement(it))
                }.toMutableMap()
        val typesToRemove = mutableSetOf<String>()
        val additionalVisitors =
            additionalVisitorConstructors
                .map { it(schema, typesToRemove, elementChildren, emptySet()) }
                .toTypedArray()
        val visitor = CompositeVisitor(
            FilterTenantLocalFieldsVisitor(elementChildren),
            TypeRemovalVisitor(typesToRemove, elementChildren),
            *additionalVisitors
        )

        buildSchemaTraverser(schema).traverse(stubRoot, visitor)

        return SchemaTransformations(
            elementChildren = elementChildren,
            typesNamesToRemove = typesToRemove
        )
    }

    /**
     * Given a schema and a set of transformations, transform the input schema.
     */
    private fun transformSchema(
        schema: GraphQLSchema,
        transformations: SchemaTransformations
    ): GraphQLSchema = SchemaTransformer.transformSchema(schema, TransformationsVisitor(transformations))

    private fun hasTenantLocalFields(schema: GraphQLSchema): Boolean =
        schema.allTypesAsList.any { element ->
            getChildrenForElement(element)?.any { child ->
                child is GraphQLFieldDefinition && child.hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME)
            } == true
        }
}
