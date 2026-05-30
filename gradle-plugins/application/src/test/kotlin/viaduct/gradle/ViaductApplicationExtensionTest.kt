package viaduct.gradle

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.scoping.SchemaScoping

/**
 * Tests for the schema-scoping DSL surface on ViaductApplicationExtension.
 *
 * The DSL accumulates declarations across calls and rejects duplicate IDs at setter time so
 * convention plugins composed with application code cannot silently shadow each other.
 * ID-format, reserved-name, and subset validation are enforced elsewhere and are not covered
 * here.
 */
class ViaductApplicationExtensionTest {
    private lateinit var extension: ViaductApplicationExtension

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        extension = ViaductApplicationExtension(project.objects)
    }

    @Test
    fun `schemaScoping is empty by default`() {
        val scoping = extension.schemaScoping.get()
        assertEquals(emptySet(), scoping.scopeUniverse)
        assertEquals(emptyMap(), scoping.scopedSchemas)
        assertFalse(scoping.isScoped)
    }

    @Test
    fun `declaredSchemaScopes populates the universe`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        assertEquals(setOf("public", "internal"), extension.schemaScoping.get().scopeUniverse)
    }

    @Test
    fun `multiple declaredSchemaScopes calls accumulate into a single universe`() {
        extension.declaredSchemaScopes(setOf("public"))
        extension.declaredSchemaScopes(setOf("internal", "admin"))
        assertEquals(
            setOf("public", "internal", "admin"),
            extension.schemaScoping.get().scopeUniverse,
        )
    }

    @Test
    fun `declaredScopedSchema records each scoped-schema entry`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        extension.declaredScopedSchema("PUBLIC_API", setOf("public"))
        extension.declaredScopedSchema("INTERNAL_API", setOf("public", "internal"))

        val schemas = extension.schemaScoping.get().scopedSchemas
        assertEquals(
            mapOf(
                "PUBLIC_API" to setOf("public"),
                "INTERNAL_API" to setOf("public", "internal"),
            ),
            schemas,
        )
    }

    @Test
    fun `declaredScopedSchema accepts an empty scope set as a full-schema alias`() {
        extension.declaredSchemaScopes(setOf("public"))
        extension.declaredScopedSchema("FULL_ALIAS", emptySet())

        val schemas = extension.schemaScoping.get().scopedSchemas
        assertTrue(schemas.containsKey("FULL_ALIAS"))
        assertEquals(emptySet(), schemas["FULL_ALIAS"])
    }

    @Test
    fun `isScoped is false when no scopes have been declared`() {
        extension.declaredScopedSchema("FULL_ALIAS", emptySet())
        // The universe (not the scoped-schemas map) is the source of truth for `isScoped`,
        // so declaring a scoped schema in isolation still reads as unscoped here. Whether this
        // combination is rejected is the concern of validation layered on top of the DSL.
        assertFalse(extension.schemaScoping.get().isScoped)
    }

    @Test
    fun `isScoped is true once a non-empty universe is declared`() {
        extension.declaredSchemaScopes(setOf("public"))
        assertTrue(extension.schemaScoping.get().isScoped)
    }

    @Test
    fun `schemaScoping reflects state at the moment of get`() {
        extension.declaredSchemaScopes(setOf("public"))
        val first = extension.schemaScoping.get()

        extension.declaredSchemaScopes(setOf("internal"))
        extension.declaredScopedSchema("PUBLIC_API", setOf("public"))
        val second = extension.schemaScoping.get()

        // First snapshot retains the state it captured.
        assertEquals(setOf("public"), first.scopeUniverse)
        assertEquals(emptyMap(), first.scopedSchemas)

        // Second snapshot reflects the additional declarations.
        assertEquals(setOf("public", "internal"), second.scopeUniverse)
        assertEquals(mapOf("PUBLIC_API" to setOf("public")), second.scopedSchemas)
    }

    @Test
    fun `pre-existing scalar properties remain functional`() {
        // Guard against accidental regression of the existing extension surface.
        assertEquals("viaduct.api.grts", extension.grtPackageName.get())

        extension.modulePackagePrefix.set("com.example")
        assertEquals("com.example", extension.modulePackagePrefix.get())
    }

    @Test
    fun `duplicate scope IDs across multiple declaredSchemaScopes calls fail`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredSchemaScopes(setOf("public", "internal"))
        }
        // The failure message must name the duplicated scope ID so the user can find it
        // in their build script. Failing without naming the offender forces a guessing game
        // when convention plugins and application code both contribute scopes.
        assertTrue(
            ex.message!!.contains("public"),
            "Expected message to name the duplicated scope id 'public', got: ${ex.message}",
        )
    }

    @Test
    fun `duplicate schema IDs across multiple declaredScopedSchema calls fail`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        extension.declaredScopedSchema("PUBLIC_API", setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchema("PUBLIC_API", setOf("internal"))
        }
        assertTrue(
            ex.message!!.contains("PUBLIC_API"),
            "Expected message to name the duplicated schema id 'PUBLIC_API', got: ${ex.message}",
        )
    }

    @Test
    fun `assembled scoping matches a directly-constructed SchemaScoping`() {
        extension.declaredSchemaScopes(setOf("public", "internal", "admin"))
        extension.declaredScopedSchema("FULL_ALIAS", emptySet())
        extension.declaredScopedSchema("PUBLIC_API", setOf("public"))
        extension.declaredScopedSchema("INTERNAL_API", setOf("public", "internal"))

        val expected = SchemaScoping(
            scopeUniverse = setOf("public", "internal", "admin"),
            scopedSchemas = mapOf(
                "FULL_ALIAS" to emptySet(),
                "PUBLIC_API" to setOf("public"),
                "INTERNAL_API" to setOf("public", "internal"),
            ),
        )
        assertEquals(expected, extension.schemaScoping.get())
    }
}
