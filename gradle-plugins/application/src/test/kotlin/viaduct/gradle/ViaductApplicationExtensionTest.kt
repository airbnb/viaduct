package viaduct.gradle

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.service.api.scoping.SchemaScoping

/**
 * ProjectBuilder tests for the schema-scoping DSL surface on [ViaductApplicationExtension].
 *
 * These cover only the accumulation/snapshot behavior and duplicate-rejection diagnostics.
 * ID-format validation, subset checks, and plugin/task wiring are deferred to later slices.
 */
class ViaductApplicationExtensionTest {
    private lateinit var project: Project
    private lateinit var extension: ViaductApplicationExtension

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().build()
        extension = project.extensions.create(
            "viaductApplication",
            ViaductApplicationExtension::class.java,
            project.objects,
        )
    }

    @Test
    fun `schemaScoping is empty by default`() {
        assertEquals(SchemaScoping.EMPTY, extension.schemaScoping.get())
    }

    @Test
    fun `declaredSchemaScopes populates the universe`() {
        extension.declaredSchemaScopes(setOf("public", "internal"))
        assertEquals(setOf("public", "internal"), extension.schemaScoping.get().scopeUniverse)
    }

    @Test
    fun `multiple declaredSchemaScopes calls accumulate`() {
        extension.declaredSchemaScopes(setOf("public"))
        extension.declaredSchemaScopes(setOf("internal", "admin"))
        assertEquals(
            setOf("public", "internal", "admin"),
            extension.schemaScoping.get().scopeUniverse,
        )
    }

    @Test
    fun `duplicate scope IDs across multiple declaredSchemaScopes calls fail`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertFailsWith<GradleException> {
            extension.declaredSchemaScopes(setOf("public", "internal"))
        }
        assertTrue(ex.message!!.contains("public"), "message should name the duplicate: ${ex.message}")
    }

    @Test
    fun `declaredScopedSchema populates the map`() {
        extension.declaredScopedSchema("PUBLIC_API", setOf("public"))
        extension.declaredScopedSchema("INTERNAL_API", setOf("public", "internal"))
        val scoped = extension.schemaScoping.get().scopedSchemas
        assertEquals(setOf("public"), scoped["PUBLIC_API"])
        assertEquals(setOf("public", "internal"), scoped["INTERNAL_API"])
    }

    @Test
    fun `duplicate schema IDs across multiple declaredScopedSchema calls fail`() {
        extension.declaredScopedSchema("PUBLIC_API", setOf("public"))
        val ex = assertFailsWith<GradleException> {
            extension.declaredScopedSchema("PUBLIC_API", setOf("internal"))
        }
        assertTrue(ex.message!!.contains("PUBLIC_API"), "message should name the duplicate: ${ex.message}")
    }

    @Test
    fun `declaredScopedSchema with empty scope set is allowed`() {
        extension.declaredScopedSchema("FULL_ALIAS", emptySet())
        assertEquals(emptySet(), extension.schemaScoping.get().scopedSchemas["FULL_ALIAS"])
    }

    @Test
    fun `isScoped is false when scopeUniverse is empty`() {
        assertFalse(extension.schemaScoping.get().isScoped)
    }

    @Test
    fun `isScoped is true when scopeUniverse is non-empty even if scopedSchemas is empty`() {
        extension.declaredSchemaScopes(setOf("public"))
        val scoping = extension.schemaScoping.get()
        assertTrue(scoping.isScoped)
        assertTrue(scoping.scopedSchemas.isEmpty())
    }

    @Test
    fun `schemaScoping is a snapshot, not a live view`() {
        val before = extension.schemaScoping.get()
        extension.declaredSchemaScopes(setOf("public"))
        val after = extension.schemaScoping.get()
        assertNotEquals(before, after)
        assertEquals(SchemaScoping.EMPTY, before)
        assertEquals(setOf("public"), after.scopeUniverse)
    }
}
