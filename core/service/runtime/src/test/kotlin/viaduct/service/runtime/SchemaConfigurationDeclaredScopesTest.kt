package viaduct.service.runtime

import java.io.File
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.engine.api.FullSchema
import viaduct.scoping.SchemaScoping
import viaduct.scoping.SchemaScopingManifest
import viaduct.service.api.SchemaId

/**
 * Covers how declared scope definitions reach [ScopedSchemaBuilder]: which loader resolves them, which
 * configurations read them at all, and what they change about a projection once they arrive.
 */
class SchemaConfigurationDeclaredScopesTest {
    private val scoping = SchemaScoping(
        scopeUniverse = setOf("default"),
        scopedSchemas = mapOf("publicSchema" to setOf("default")),
    )

    @Test
    fun `the declared universe survives a context loader swapped between configuration and read`(
        @TempDir root: File
    ) {
        val declaring = classLoaderOver(root)

        val declaredScopes = withContextClassLoader(declaring) {
            SchemaConfiguration.declaredScopesFromResources()
        }
        val resolved = withContextClassLoader(URLClassLoader(emptyArray(), null)) {
            declaredScopes.value
        }

        assertEquals(setOf("default"), resolved)
    }

    @Test
    fun `a configuration-time loader without a manifest declares nothing`() {
        val declaredScopes = withContextClassLoader(URLClassLoader(emptyArray(), null)) {
            SchemaConfiguration.declaredScopesFromResources()
        }

        assertEquals(emptySet<String>(), declaredScopes.value)
    }

    @Test
    fun `a scoped view of a wildcard-only schema builds only when a universe is declared`() {
        val full = FullSchema(EngineRegistryTest.createSchemaFromSdl(WILDCARD_ONLY_SDL))
        val scoped = SchemaConfiguration.ScopeConfig.Scoped("publicSchema", setOf("default"))

        val message = assertThrows<IllegalArgumentException> {
            SchemaConfiguration.ScopedSchemaConfig.Derived(scoped, lazy = false).build(full)
        }.message!!
        assertTrue(message.contains("unscoped schema"), message)

        assertNotNull(
            SchemaConfiguration.ScopedSchemaConfig
                .Derived(scoped, lazy = false, lazyOf(setOf("default")))
                .build(full),
        )
    }

    @Test
    fun `a view naming a scope the declaration omits is rejected even when the schema mentions it`() {
        val full = FullSchema(EngineRegistryTest.createSchemaFromSdl(TWO_SCOPE_SDL))
        val extrasOnly = SchemaConfiguration.ScopeConfig.Scoped("extrasSchema", setOf("extras"))

        assertNotNull(SchemaConfiguration.ScopedSchemaConfig.Derived(extrasOnly, lazy = false).build(full))

        val message = assertThrows<IllegalArgumentException> {
            SchemaConfiguration.ScopedSchemaConfig
                .Derived(extrasOnly, lazy = false, lazyOf(setOf("default")))
                .build(full)
        }.message!!
        assertTrue(message.contains("unknown scopes: [extras]"), message)
    }

    @Test
    fun `a base-only configuration reads no definitions, so an ambiguous classpath cannot fail it`(
        @TempDir root: File
    ) {
        val ambiguous = classLoaderOver(root, "one", "two")
        val full = FullSchema(EngineRegistryTest.createSchemaFromSdl(TWO_SCOPE_SDL))

        val baseOnly = withContextClassLoader(ambiguous) {
            SchemaConfiguration.fromResources(scopes = setOf(SchemaConfiguration.ScopeConfig.Base))
        }
        assertNotNull(baseOnly.scopedSchemas.getValue(SchemaId.Base).build(full))

        val alsoScoped = withContextClassLoader(ambiguous) {
            SchemaConfiguration.fromResources(
                scopes = setOf(
                    SchemaConfiguration.ScopeConfig.Base,
                    SchemaConfiguration.ScopeConfig.Scoped("publicSchema", setOf("default")),
                ),
            )
        }
        assertThrows<IllegalStateException> {
            alsoScoped.scopedSchemas.getValue(SchemaId.Scoped("publicSchema", setOf("default"))).build(full)
        }
    }

    private fun <T> withContextClassLoader(
        classLoader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = original
        }
    }

    private fun classLoaderOver(
        root: File,
        vararg names: String,
    ): URLClassLoader {
        val roots = if (names.isEmpty()) listOf(root) else names.map { root.resolve(it) }
        val urls = roots.map { classpathRoot ->
            classpathRoot.resolve(SchemaScopingManifest.RESOURCE_PATH).apply {
                parentFile.mkdirs()
                writeText(SchemaScopingManifest.encode(scoping))
            }
            classpathRoot.toURI().toURL()
        }
        return URLClassLoader(urls.toTypedArray(), null)
    }

    private companion object {
        const val WILDCARD_ONLY_SDL = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

            type Query @scope(to: ["*"]) {
                hello: String
            }
        """

        const val TWO_SCOPE_SDL = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

            type Query @scope(to: ["default", "extras"]) {
                hello: String
            }
        """
    }
}
