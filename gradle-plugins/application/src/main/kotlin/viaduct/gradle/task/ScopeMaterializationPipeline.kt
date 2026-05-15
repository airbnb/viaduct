package viaduct.gradle.task

import graphql.GraphQL
import graphql.introspection.IntrospectionQuery
import graphql.schema.GraphQLSchema
import java.io.File
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.GradleException
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.graphql.schema.scopes.ResourceFileSchema
import viaduct.graphql.schema.scopes.ScopedMode

/**
 * Encapsulates scope materialization with memoization.
 *
 * For each unique scope set, [materialize] produces a filtered [GraphQLSchema].
 * An empty scope set returns the original [fullSchema] unchanged (FULL semantics).
 * Results are cached by scope set so aliases sharing the same set pay the cost once.
 */
internal class ScopeMaterializationPipeline(
    val fullSchema: GraphQLSchema,
    private val scopeUniverse: SortedSet<String>,
) {
    private val scopedBuilder = ScopedSchemaBuilder(fullSchema, scopeUniverse, emptyList())
    private val cache = ConcurrentHashMap<Set<String>, GraphQLSchema>()

    val materializationCount: Int get() = cache.size

    fun materialize(scopeSet: Set<String>): GraphQLSchema = cache.computeIfAbsent(scopeSet) {
        if (scopeSet.isEmpty()) {
            fullSchema
        } else {
            scopedBuilder.applyScopes(scopeSet).filtered
        }
    }
}

/**
 * Runs an introspection query against [schema] and throws [GradleException] if errors are present.
 */
internal fun validateScopeSchemaWithIntrospection(schema: GraphQLSchema, scopeSet: Set<String>) {
    val result = GraphQL.newGraphQL(schema).build().execute(IntrospectionQuery.INTROSPECTION_QUERY)
    if (result.errors.isNotEmpty()) {
        val errMsg = result.errors.joinToString("\n") { it.message ?: it.toString() }
        throw GradleException("Schema introspection failed for scope set $scopeSet:\n$errMsg")
    }
}

/**
 * Writes the scope resource file to [outputDirectory]/META-INF/viaduct/schema-scoping.json.
 */
internal fun writeScopeResourceFile(outputDirectory: File, scopedMode: ScopedMode) {
    val resourceFileSchema = ResourceFileSchema.create(
        declaredSchemaScopes = scopedMode.scopeUniverse,
        declaredScopedSchemas = scopedMode.scopedSchemas,
    )
    val json = ResourceFileSchema.objectMapper().writeValueAsString(resourceFileSchema)
    val resourceFile = outputDirectory.resolve("META-INF/viaduct/schema-scoping.json")
    resourceFile.parentFile.mkdirs()
    resourceFile.writeText(json)
}
