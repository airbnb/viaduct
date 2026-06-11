package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import viaduct.service.api.scoping.SchemaScoping

open class ViaductApplicationExtension(objects: ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")

    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)

    /** Port for the development server. Defaults to 8080. Set to 0 for dynamic port allocation. */
    val servePort = objects.property(Int::class.java).convention(8080)

    /** Host address for the development server. Defaults to "0.0.0.0". */
    val serveHost = objects.property(String::class.java).convention("0.0.0.0")

    // --- Schema scoping -----------------------------------------------------

    /** Private storage for the declared scope universe. */
    private val _scopeUniverse: SetProperty<String> =
        objects.setProperty(String::class.java)

    /** Private storage for the declared scoped schemas. */
    private val _scopedSchemas: MapProperty<String, ScopedSchemaDefinition> =
        objects.mapProperty(String::class.java, ScopedSchemaDefinition::class.java)

    // Eager bookkeeping used solely to reject duplicate declarations loudly, with a
    // precise stack trace at the offending DSL call. The lazy Gradle Properties above
    // remain the source of truth for the materialized SchemaScoping value.
    private val seenScopes = mutableSetOf<String>()
    private val seenSchemaIds = mutableSetOf<String>()

    /**
     * The canonical, public scope-state surface: a [Provider] that materializes the
     * accumulated DSL declarations into a [SchemaScoping] snapshot. Re-evaluated on each
     * `get()`, so it always reflects the declarations made so far.
     */
    @get:org.gradle.api.tasks.Input
    val schemaScoping: Provider<SchemaScoping> =
        _scopeUniverse.zip(_scopedSchemas) { universe, schemas ->
            SchemaScoping(
                scopeUniverse = universe,
                scopedSchemas = schemas.mapValues { it.value.scopeSet },
            )
        }

    /**
     * Declares scope IDs that make up the application's scope universe. Multiple calls
     * accumulate. Declaring a scope ID that was already declared (in this or a prior call)
     * fails the build, naming the duplicate.
     */
    fun declaredSchemaScopes(scopes: Set<String>) {
        val duplicates = scopes.filter { !seenScopes.add(it) }
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "declaredSchemaScopes was given scope id(s) that were already declared: " +
                    "${duplicates.sorted()}."
            )
        }
        _scopeUniverse.addAll(scopes)
    }

    /**
     * Declares a scoped schema [id] restricted to the given [scopes]. An empty [scopes] set
     * denotes an alias for the full schema. Declaring an [id] that was already declared fails
     * the build, naming the duplicate.
     */
    fun declaredScopedSchema(
        id: String,
        scopes: Set<String>
    ) {
        if (!seenSchemaIds.add(id)) {
            throw GradleException(
                "declaredScopedSchema was given a scoped-schema id that was already declared: '$id'."
            )
        }
        _scopedSchemas.put(id, ScopedSchemaDefinition(scopes))
    }
}
