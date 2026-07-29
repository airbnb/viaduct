package viaduct.graphql.scopes

import graphql.GraphQL
import graphql.introspection.IntrospectionQuery
import graphql.schema.GraphQLSchema
import java.util.SortedSet

/**
 * Build-time helper that materializes filtered schema projections for each declared scope set and
 * asserts that each projection is introspectable.
 *
 * This is the load-bearing helper for slice 5 of the schema-scoping DSL (#361 / #374). The caller
 * (typically `AssembleCentralSchemaTask`) is responsible for computing the *set* of scope sets to
 * materialize — including the always-added universe set — and reporting failures. This helper does
 * exactly one thing per `validate` call: run `ScopedSchemaBuilder.applyScopes(scopeSet)` and then
 * `IntrospectionQuery.INTROSPECTION_QUERY` against the filtered schema, returning any errors as
 * [Failure] records that carry the scope set as the sole identifying key.
 *
 * ### Why the API is scope-set-keyed, not alias-keyed
 *
 * Aliases are user-facing labels (e.g. `PUBLIC_API`) and can collide, rename, or exist purely as an
 * empty-set full-schema alias — they carry no validation identity. The scope set is the only value
 * that determines what filtered projection actually materializes. Keeping the helper's surface
 * scope-set-only prevents downstream diagnostics from leaking alias names into failure messages
 * (see the slice-5 test `failure diagnostic names the scope set, not the alias`).
 *
 * ### Why `includeTenantLocalFields` toggles on universe-set materialization
 *
 * `ScopedSchemaBuilder.applyScopes` only runs the A.9 root-type-scope check (via
 * `ValidateRequiredScopesVisitor` in `SchemaScopeTransformer.kt:110-113`) when
 * `appliedScopes == validScopes && includeTenantLocalFields`. Passing `includeTenantLocalFields =
 * true` for the universe-set case makes the build-time materialization behave as the runtime
 * `SchemaId.Base` projection would (tenant-local fields kept + A.9 fires on root types). Explicit
 * scoped-schema subsets use `includeTenantLocalFields = false` to match their external-visible
 * runtime shape.
 *
 * ### Cross-module dependency note
 *
 * This module (`core/shared/graphql`) cannot depend on `core/shared/viaductschema` — the reverse
 * edge already exists. So [Failure] is a helper-local data class, not a
 * `viaduct.graphql.schema.validation.SchemaValidationError`. The caller formats each failure into
 * whatever downstream error type it uses, reading the `SCOPED_SCHEMA_INTROSPECTION_FAILED`
 * constant from `ValidationErrorCodes` on its own side of the module boundary.
 */
class ScopedSchemaValidator(
    private val inputSchema: GraphQLSchema,
    private val validScopes: SortedSet<String>,
    additionalVisitorConstructors: List<AdditionalVisitorConstructor> = emptyList(),
) {
    private val builder = ScopedSchemaBuilder(inputSchema, validScopes, additionalVisitorConstructors)
    private val _validatedScopeSets: MutableList<Set<String>> = mutableListOf()

    /**
     * Every scope set passed to [validate], in call order. Exposed for test observability —
     * unit tests pin the "no dedup at helper level" (caller's job), "safe on repeat calls",
     * and "safe on empty input" contracts by reading this accumulator. Production callers
     * (currently `AssembleCentralSchemaTask.validateScopedSchemas`) iterate their own scope-set
     * list and do not read this property; keep changes here in sync with the test contract.
     */
    val validatedScopeSets: List<Set<String>> get() = _validatedScopeSets.toList()

    /**
     * Materialize [scopeSet] against the input schema and return any [Failure] records for
     * introspection errors (or materialization exceptions surfaced as a single failure).
     *
     * @return empty list on success; one or more [Failure]s on introspection or materialization
     *         error. Never throws — materialization exceptions are captured as failures so the
     *         caller can aggregate across all scope sets before reporting.
     */
    fun validate(scopeSet: Set<String>): List<Failure> {
        _validatedScopeSets.add(scopeSet)

        val isUniverse = scopeSet == validScopes.toSet()
        val filtered: GraphQLSchema = try {
            builder.applyScopes(scopeSet, includeTenantLocalFields = isUniverse).filtered
        } catch (e: Exception) {
            return listOf(
                Failure(
                    scopeSet = scopeSet,
                    message = "materialization failed: ${e.message ?: e.javaClass.simpleName}"
                )
            )
        }

        val result = GraphQL.newGraphQL(filtered).build().execute(IntrospectionQuery.INTROSPECTION_QUERY)
        if (result.errors.isEmpty()) return emptyList()
        return result.errors.map { err ->
            Failure(
                scopeSet = scopeSet,
                message = "introspection error: ${err.message}"
            )
        }
    }

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
