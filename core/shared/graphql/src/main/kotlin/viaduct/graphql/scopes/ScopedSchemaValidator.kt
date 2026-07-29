package viaduct.graphql.scopes

import graphql.GraphQL
import graphql.introspection.IntrospectionQuery
import graphql.schema.GraphQLSchema
import java.util.SortedSet
import viaduct.graphql.scopes.errors.SchemaScopeValidationError

/**
 * Materializes filtered schema projections of an input schema and asserts each is
 * introspectable, returning any errors as [Failure] records.
 *
 * Two entry points:
 * - [validate] materializes a single explicit scope set via
 *   `ScopedSchemaBuilder.applyScopes(scopeSet, includeTenantLocalFields = false)` — matching the
 *   runtime shape of `SchemaId.Scoped` — and returns failures keyed by the scope set.
 * - [validateBase] materializes the base schema via `ScopedSchemaBuilder.applyBaseSchema()` —
 *   matching the runtime shape of `SchemaId.Base` (the default when no `schemaId` is passed to
 *   `Viaduct.execute`) — and returns failure messages.
 *
 * Neither entry point throws. Materialization exceptions — including [SchemaScopeValidationError],
 * which extends `Throwable` rather than `Exception` — are captured so a caller iterating across
 * many scope sets can aggregate all failures before reporting.
 *
 * ### Why the API is scope-set-keyed, not alias-keyed
 *
 * Aliases are user-facing labels and can collide, rename, or exist as empty-set entries that map
 * to the base schema. The scope set is the only value that determines what filtered projection
 * materializes. A scope-set-keyed surface prevents downstream diagnostics from leaking alias
 * names into failure messages.
 *
 * ### Cross-module dependency note
 *
 * This module (`core/shared/graphql`) cannot depend on `core/shared/viaductschema` — the reverse
 * edge already exists — so [Failure] is a helper-local data class rather than a
 * `viaduct.graphql.schema.validation.SchemaValidationError`. The caller formats each failure into
 * its own diagnostic shape and error-code prefix.
 */
class ScopedSchemaValidator(
    private val inputSchema: GraphQLSchema,
    private val validScopes: SortedSet<String>,
    additionalVisitorConstructors: List<AdditionalVisitorConstructor> = emptyList(),
) {
    private val builder = ScopedSchemaBuilder(inputSchema, validScopes, additionalVisitorConstructors)
    private val _validatedScopeSets: MutableList<Set<String>> = mutableListOf()
    private var _basesValidated: Int = 0

    /**
     * Every scope set passed to [validate], in call order. Exposed for test observability —
     * production callers iterate their own scope-set list and do not read this property.
     */
    val validatedScopeSets: List<Set<String>> get() = _validatedScopeSets.toList()

    /** Count of [validateBase] calls. Exposed for test observability. */
    val basesValidated: Int get() = _basesValidated

    /**
     * Materialize [scopeSet] against the input schema and return any [Failure] records for
     * introspection errors (or materialization exceptions surfaced as a single failure).
     *
     * @return empty list on success; one or more [Failure]s otherwise. Never throws.
     */
    fun validate(scopeSet: Set<String>): List<Failure> {
        _validatedScopeSets.add(scopeSet)
        val filtered = try {
            builder.applyScopes(scopeSet, includeTenantLocalFields = false).filtered
        } catch (e: SchemaScopeValidationError) {
            return listOf(Failure(scopeSet, materializationMessage(e)))
        } catch (e: Exception) {
            return listOf(Failure(scopeSet, materializationMessage(e)))
        }
        return introspect(filtered).map { Failure(scopeSet, "introspection error: $it") }
    }

    /**
     * Materialize the base schema (runtime `SchemaId.Base`) and return any error messages.
     *
     * The base schema is the tenant-local-stripped projection every application executes as its
     * default when a caller passes no explicit `schemaId` to `Viaduct.execute`; build-time
     * introspection coverage for it therefore applies to both scoped and unscoped applications.
     *
     * @return empty list on success; one or more error messages otherwise. Never throws.
     */
    fun validateBase(): List<String> {
        _basesValidated++
        val filtered = try {
            builder.applyBaseSchema().filtered
        } catch (e: SchemaScopeValidationError) {
            return listOf(materializationMessage(e))
        } catch (e: Exception) {
            return listOf(materializationMessage(e))
        }
        return introspect(filtered).map { "introspection error: $it" }
    }

    private fun introspect(filtered: GraphQLSchema): List<String> {
        val result = GraphQL.newGraphQL(filtered).build().execute(IntrospectionQuery.INTROSPECTION_QUERY)
        return result.errors.map { it.message ?: it.toString() }
    }

    private fun materializationMessage(t: Throwable): String =
        "materialization failed: ${t.message ?: t.javaClass.simpleName}"

    /**
     * A single validation failure, keyed by the scope set that produced it.
     *
     * @property scopeSet the scope set the caller passed to [validate] — never an alias name.
     * @property message a human-readable diagnostic; the caller is responsible for prefixing an
     *                   error code and formatting a location.
     */
    data class Failure(
        val scopeSet: Set<String>,
        val message: String,
    )
}
