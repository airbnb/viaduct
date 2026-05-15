package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import viaduct.graphql.schema.scopes.NoScopesMode
import viaduct.graphql.schema.scopes.ScopeIdSyntax
import viaduct.graphql.schema.scopes.ScopeMode
import viaduct.graphql.schema.scopes.ScopedMode

open class ViaductApplicationExtension(objects: ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")

    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)

    /** Port for the development server. Defaults to 8080. Set to 0 for dynamic port allocation. */
    val servePort = objects.property(Int::class.java).convention(8080)

    /** Host address for the development server. Defaults to "0.0.0.0". */
    val serveHost = objects.property(String::class.java).convention("0.0.0.0")

    /** Declared scope universe — Gradle @Input property for UP-TO-DATE and config-cache compatibility. */
    @get:Input
    val scopeUniverse: SetProperty<String> = objects.setProperty(String::class.java)

    /**
     * Serialized scoped-schema entries — each entry is "schema-id=scope1,scope2,..." (scopes sorted).
     * Gradle @Input property for stable UP-TO-DATE hashing.
     */
    @get:Input
    val scopedSchemaEntries: SetProperty<String> = objects.setProperty(String::class.java)

    private val _scopedSchemas: MutableMap<String, Set<String>> = mutableMapOf()

    /**
     * Declares the scope universe for this project.
     * Validates syntax via [ScopeIdSyntax] and rejects duplicate IDs.
     * May be called multiple times; each call appends to the universe.
     */
    fun declaredSchemaScopes(scopes: Set<String>) {
        scopes.forEach { scope ->
            ScopeIdSyntax.validate(scope).getOrElse { ex ->
                throw GradleException(ex.message ?: "Invalid scope ID: $scope")
            }
        }
        val currentUniverse = scopeUniverse.getOrElse(emptySet())
        val duplicates = scopes intersect currentUniverse
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "Duplicate scope IDs declared: $duplicates. Each scope ID may only be declared once."
            )
        }
        val sortedNew = scopes.toSortedSet()
        scopeUniverse.addAll(sortedNew)
    }

    /**
     * Declares that a schema resource file (identified by [id]) is restricted to [scopes].
     * All [scopes] must first be declared via [declaredSchemaScopes].
     * Validates the schema ID syntax via [ScopeIdSyntax].
     */
    fun declaredScopedSchema(id: String, scopes: Set<String>) {
        ScopeIdSyntax.validate(id).getOrElse { ex ->
            throw GradleException(ex.message ?: "Invalid scoped-schema ID: $id")
        }
        val currentUniverse = scopeUniverse.getOrElse(emptySet())
        val notInUniverse = scopes - currentUniverse
        if (notInUniverse.isNotEmpty()) {
            throw GradleException(
                "Scoped schema '$id' declares scopes not in the scope universe: $notInUniverse. " +
                    "Declare them first with declaredSchemaScopes()."
            )
        }
        _scopedSchemas[id] = scopes.toSortedSet()
        scopedSchemaEntries.add("$id=${scopes.sorted().joinToString(",")}")
    }

    /**
     * Returns the scope configuration as a [ScopeMode]:
     * - [NoScopesMode] when no scopes have been declared (backward-compatible default).
     * - [ScopedMode] once [declaredSchemaScopes] has been called.
     */
    fun getSchemaScopeInfo(): ScopeMode {
        val universe = scopeUniverse.getOrElse(emptySet())
        return if (universe.isEmpty()) {
            NoScopesMode
        } else {
            ScopedMode(
                scopeUniverse = universe,
                scopedSchemas = _scopedSchemas.toMap()
            )
        }
    }
}
