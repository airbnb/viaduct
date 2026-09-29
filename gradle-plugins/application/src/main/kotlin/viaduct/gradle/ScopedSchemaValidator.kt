package viaduct.gradle

import graphql.GraphQL
import graphql.introspection.IntrospectionQuery
import graphql.schema.GraphQLSchema
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.scopes.SchemaDeprecatedDirectiveVisitor
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser
import viaduct.service.api.scoping.SchemaScoping

/**
 * Builds every scoped schema an application declares and checks each one is still a valid,
 * introspectable GraphQL schema.
 *
 * Building a view is also what checks `@scope(to: [...])` names against the declared universe; there
 * is no separate check. Fields leaving a projection is the point of scoping, so nothing reports it.
 */
@OptIn(ExperimentalApi::class)
internal object ScopedSchemaValidator {
    /**
     * Returns one message per failed check, empty when every declared scoped schema builds. Failures
     * accumulate rather than throwing, except that a failure no view can affect ends the sweep instead
     * of repeating for every scope set.
     */
    fun validate(
        schema: GraphQLSchema,
        scoping: SchemaScoping,
    ): List<String> {
        if (!scoping.isScoped) return emptyList()
        val builder = ScopedSchemaBuilder(
            schema,
            SchemaScopingMode.ScopeAware(scoping.scopeUniverse),
            emptyList(),
        )
        val failures = mutableListOf<String>()
        for (target in targets(scoping)) {
            val outcome = validate(builder, target)
            failures += outcome.messages
            if (outcome.viewIndependent) break
        }
        return failures
    }

    private fun targets(scoping: SchemaScoping): List<Target> {
        val idsByScopeSet = linkedMapOf<Set<String>, MutableList<String>>(scoping.scopeUniverse to mutableListOf())
        scoping.scopedSchemas.toSortedMap().forEach { (id, scopes) ->
            idsByScopeSet.getOrPut(scopes) { mutableListOf() }.add(id)
        }
        return idsByScopeSet.map { (scopes, ids) ->
            Target(
                scopes = scopes,
                names = buildList {
                    if (scopes == scoping.scopeUniverse) add("the declared scope universe")
                    if (ids.isNotEmpty()) {
                        val plural = if (ids.size > 1) "s" else ""
                        add("scoped schema$plural ${ids.joinToString(", ") { "'$it'" }}")
                    }
                },
            )
        }
    }

    private fun validate(
        builder: ScopedSchemaBuilder,
        target: Target,
    ): Outcome =
        try {
            val scopedSchema = builder.build(SchemaView.Scoped(target.scopes)).filtered
            Outcome(introspectionFailures(scopedSchema, target) + deprecatedDirectiveFailures(scopedSchema, target))
        } catch (e: SchemaScopeValidationError) {
            // Scope directives are checked against the universe, not the view, so every view repeats this.
            Outcome(listOf("Could not build $target: ${e.message}"), viewIndependent = true)
        } catch (e: Exception) {
            Outcome(listOf("Could not build $target: ${e.message ?: e.toString()}"))
        }

    /** graphql-java does not enforce every spec rule at construction, so run an introspection query. */
    private fun introspectionFailures(
        scopedSchema: GraphQLSchema,
        target: Target,
    ): List<String> {
        val result = GraphQL.newGraphQL(scopedSchema).build().execute(IntrospectionQuery.INTROSPECTION_QUERY)
        return if (result.errors.isEmpty()) {
            emptyList()
        } else {
            listOf("Introspection failed for $target: ${result.errors}")
        }
    }

    private fun deprecatedDirectiveFailures(
        scopedSchema: GraphQLSchema,
        target: Target,
    ): List<String> {
        val deprecated = scopedSchema.getDirective("deprecated") ?: return emptyList()
        val visitor = SchemaDeprecatedDirectiveVisitor(deprecated.validLocations())
        buildSchemaTraverser(scopedSchema).traverse(StubRoot(scopedSchema), visitor)
        if (visitor.invalidElements.isEmpty()) return emptyList()
        return listOf(
            "Invalid @deprecated usage in $target: " +
                visitor.invalidElements.joinToString("; ") { (element, problem) -> "$problem on ${element.name}" },
        )
    }

    private class Outcome(
        val messages: List<String>,
        val viewIndependent: Boolean = false,
    )

    private class Target(
        val scopes: Set<String>,
        private val names: List<String>,
    ) {
        override fun toString() = "${names.joinToString(" and ")} (scopes: ${scopes.sorted()})"
    }
}
