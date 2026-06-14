package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import viaduct.apiannotations.ExperimentalApi
import viaduct.service.api.scoping.SchemaScoping
import viaduct.service.api.scoping.ScopingErrorCodes

@OptIn(ExperimentalApi::class)
open class ViaductApplicationExtension(objects: ObjectFactory) {
    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)

    private val scopeUniverseProperty: SetProperty<String> =
        objects.setProperty(String::class.java)

    private val scopedSchemasProperty: MapProperty<String, ScopedSchemaDefinition> =
        objects.mapProperty(String::class.java, ScopedSchemaDefinition::class.java)

    private var scopeUniverseDeclared = false
    private var scopedSchemasDeclared = false

    /**
     * Canonical view of the application's schema-scoping declarations, derived from the
     * `declaredSchemaScopes` and `declaredScopedSchemas` DSL methods. Re-evaluated on each `get()`,
     * so successive snapshots reflect the state at the moment of resolution.
     */
    val schemaScoping: Provider<SchemaScoping> =
        scopeUniverseProperty.zip(scopedSchemasProperty) { universe, schemas ->
            SchemaScoping(
                scopeUniverse = universe,
                scopedSchemas = schemas.mapValues { it.value.scopeSet },
            )
        }

    /**
     * Declares the scope universe for this application. May be called at most once with a
     * non-empty set; the call is the single decision-point for what scopes exist. Omit the call
     * entirely to express "no scoping". Convention plugins that contribute baseline scopes
     * compose at the call site rather than via repeated mutation. An empty set or a second call
     * is rejected with a [GradleException].
     */
    fun declaredSchemaScopes(scopes: Set<String>) {
        if (scopeUniverseDeclared) {
            throw GradleException(
                "[${ScopingErrorCodes.SCHEMA_SCOPES_DECLARED_TWICE}] declaredSchemaScopes may only be called once. " +
                    "Compose convention-plugin contributions into a single Set before the call.",
            )
        }
        if (scopes.isEmpty()) {
            throw GradleException(
                "[${ScopingErrorCodes.SCHEMA_SCOPES_EMPTY}] declaredSchemaScopes requires at least one scope ID. " +
                    "Omit the call entirely if this application does not declare scopes.",
            )
        }
        scopeUniverseDeclared = true
        scopeUniverseProperty.set(scopes)
    }

    /**
     * Declares the application's scoped schemas as a fixed map from schema ID to its scope set.
     * May be called at most once with at least one entry; the call is the single decision-point
     * for which scoped schemas exist. An empty value set per entry (e.g. `"FULL_ALIAS" to
     * emptySet()`) is allowed and acts as an alias for the full schema. A second call, an empty
     * varargs list, or duplicate schema IDs within the single call are rejected with a
     * [GradleException].
     */
    fun declaredScopedSchemas(vararg entries: Pair<String, Set<String>>) {
        if (scopedSchemasDeclared) {
            throw GradleException(
                "[${ScopingErrorCodes.SCOPED_SCHEMAS_DECLARED_TWICE}] declaredScopedSchemas may only be called once. " +
                    "Compose convention-plugin contributions into a single varargs invocation.",
            )
        }
        if (entries.isEmpty()) {
            throw GradleException(
                "[${ScopingErrorCodes.SCOPED_SCHEMAS_EMPTY}] declaredScopedSchemas requires at least one scoped-schema entry. " +
                    "Omit the call entirely if this application does not declare scoped schemas.",
            )
        }
        val duplicates = entries.groupBy { it.first }.filter { it.value.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "[${ScopingErrorCodes.SCOPED_SCHEMA_DUPLICATE_ID}] Duplicate scoped-schema ID(s) declared via declaredScopedSchemas: " +
                    "$duplicates. Each scoped-schema ID may only appear once.",
            )
        }
        scopedSchemasDeclared = true
        scopedSchemasProperty.set(
            entries.associate { (id, scopes) -> id to ScopedSchemaDefinition(scopes) },
        )
    }
}
