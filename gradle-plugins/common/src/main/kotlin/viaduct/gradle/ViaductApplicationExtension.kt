package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import viaduct.apiannotations.ExperimentalApi
import viaduct.service.api.scoping.SchemaScoping

@OptIn(ExperimentalApi::class)
open class ViaductApplicationExtension(objects: ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")

    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)

    private val scopeUniverseProperty: SetProperty<String> =
        objects.setProperty(String::class.java)

    private val scopedSchemasProperty: MapProperty<String, ScopedSchemaDefinition> =
        objects.mapProperty(String::class.java, ScopedSchemaDefinition::class.java)

    /**
     * Canonical view of the application's schema-scoping declarations, derived from the
     * `declaredSchemaScopes` and `declaredScopedSchema` DSL methods. Re-evaluated on each `get()`,
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
     * Adds [scopes] to the declared scope universe. May be called multiple times; calls accumulate
     * via set union. A scope ID that already appears in the universe from an earlier call is
     * rejected with a [GradleException] naming the duplicate, so that convention plugins and
     * application code can't silently shadow each other.
     */
    fun declaredSchemaScopes(scopes: Set<String>) {
        val existing = scopeUniverseProperty.getOrElse(emptySet())
        val duplicates = scopes intersect existing
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "Duplicate scope ID(s) declared via declaredSchemaScopes: " +
                    "${duplicates.sorted()}. Each scope ID may only be declared once.",
            )
        }
        scopeUniverseProperty.addAll(scopes)
    }

    /**
     * Declares that schema [id] is restricted to [scopes]. An empty [scopes] set is permitted and
     * acts as an alias for the full schema. A schema ID that was already declared by an earlier
     * call is rejected with a [GradleException] naming the duplicate, so that convention plugins
     * and application code can't silently overwrite each other.
     */
    fun declaredScopedSchema(id: String, scopes: Set<String>) {
        val existing = scopedSchemasProperty.getOrElse(emptyMap())
        if (id in existing) {
            throw GradleException(
                "Duplicate scoped-schema ID declared via declaredScopedSchema: " +
                    "'$id'. Each scoped-schema ID may only be declared once.",
            )
        }
        scopedSchemasProperty.put(id, ScopedSchemaDefinition(scopes))
    }
}
