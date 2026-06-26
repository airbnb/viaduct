package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.apiannotations.ExperimentalApi
import viaduct.service.api.scoping.SchemaScoping

/**
 * Tests for the schema-scoping DSL surface on ViaductApplicationExtension.
 *
 * The DSL is single-call immutable: each declaration setter may be invoked at most once and
 * rejects empty inputs, so convention plugins composed with application code must compose at
 * the call site rather than mutate across calls. ID-format, reserved-name, and subset
 * validation are enforced elsewhere and are not covered here.
 */
@OptIn(ExperimentalApi::class)
class ViaductApplicationExtensionTest {
    private lateinit var extension: ViaductApplicationExtension

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        extension = ViaductApplicationExtension(project.objects)
    }

    @Test
    fun `scope universe is empty by default`() {
        assertEquals(emptySet<String>(), extension.scopeUniverseProperty.get())
        assertEquals(emptyMap<String, ScopedSchemaDefinition>(), extension.scopedSchemasProperty.get())
    }

    @Test
    fun `declaredSchemaScopes populates the universe`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        assertEquals(setOf("public", "internal"), extension.scopeUniverseProperty.get())
    }

    @Test
    fun `second declaredSchemaScopes call is rejected`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredSchemaScopes(setOf("internal"))
        }
        assertTrue(
            ex.message!!.contains("only be called once"),
            "Expected message to explain the single-call constraint, got: ${ex.message}",
        )
    }

    @Test
    fun `declaredSchemaScopes rejects an empty set`() {
        val ex = assertThrows<GradleException> {
            extension.declaredSchemaScopes(emptySet())
        }
        assertTrue(
            ex.message!!.contains("at least one"),
            "Expected message to direct caller to omit the call instead, got: ${ex.message}",
        )
    }

    @Test
    fun `declaredScopedSchemas records each scoped-schema entry`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        extension.declaredScopedSchemas(
            "PUBLIC_API" to setOf("public"),
            "INTERNAL_API" to setOf("public", "internal"),
        )

        val schemas = extension.scopedSchemasProperty.get()
        assertEquals(
            mapOf(
                "PUBLIC_API" to ScopedSchemaDefinition(setOf("public")),
                "INTERNAL_API" to ScopedSchemaDefinition(setOf("public", "internal")),
            ),
            schemas,
        )
    }

    @Test
    fun `declaredScopedSchemas accepts an empty scope set as a full-schema alias`() {
        extension.declaredSchemaScopes(setOf("public"))
        extension.declaredScopedSchemas("FULL_ALIAS" to emptySet())

        val schemas = extension.scopedSchemasProperty.get()
        assertTrue(schemas.containsKey("FULL_ALIAS"))
        assertEquals(ScopedSchemaDefinition(emptySet()), schemas["FULL_ALIAS"])
    }

    @Test
    fun `second declaredScopedSchemas call is rejected`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        extension.declaredScopedSchemas("PUBLIC_API" to setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchemas("INTERNAL_API" to setOf("internal"))
        }
        assertTrue(
            ex.message!!.contains("only be called once"),
            "Expected message to explain the single-call constraint, got: ${ex.message}",
        )
    }

    @Test
    fun `declaredScopedSchemas rejects an empty entries list`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchemas()
        }
        assertTrue(
            ex.message!!.contains("at least one"),
            "Expected message to direct caller to omit the call instead, got: ${ex.message}",
        )
    }

    @Test
    fun `duplicate scoped-schema IDs within a single declaredScopedSchemas call fail`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchemas(
                "PUBLIC_API" to setOf("public"),
                "PUBLIC_API" to setOf("internal"),
            )
        }
        assertTrue(
            ex.message!!.contains("PUBLIC_API"),
            "Expected message to name the duplicated schema id 'PUBLIC_API', got: ${ex.message}",
        )
    }

    @Test
    fun `isScoped is false when no scopes have been declared`() {
        extension.declaredScopedSchemas("FULL_ALIAS" to emptySet())
        assertFalse(extension.scopeUniverseProperty.get().isNotEmpty())
    }

    @Test
    fun `isScoped is true once a non-empty universe is declared`() {
        extension.declaredSchemaScopes(setOf("public"))
        assertTrue(extension.scopeUniverseProperty.get().isNotEmpty())
    }

    @Test
    fun `schemaScoping internal provider assembles the correct SchemaScoping`() {
        extension.declaredSchemaScopes(setOf("public", "internal", "admin"))
        extension.declaredScopedSchemas(
            "FULL_ALIAS" to emptySet(),
            "PUBLIC_API" to setOf("public"),
            "INTERNAL_API" to setOf("public", "internal"),
        )

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

    @Test
    fun `pre-existing scalar properties remain functional`() {
        extension.modulePackagePrefix.set("com.example")
        assertEquals("com.example", extension.modulePackagePrefix.get())
    }
}
