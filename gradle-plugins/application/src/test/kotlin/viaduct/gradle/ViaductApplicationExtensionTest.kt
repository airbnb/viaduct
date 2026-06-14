package viaduct.gradle

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
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
 * the call site rather than mutate across calls. Per-ID syntax and reserved-name checks fire
 * synchronously at setter time (covered here); cross-property subset/universe-presence invariants
 * fire in the plugin's `afterEvaluate` hook and are covered by the TestKit suite.
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
    fun `second declaredSchemaScopes call is rejected`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredSchemaScopes(setOf("internal"))
        }
        // The message must name the single-call constraint so the caller understands the
        // composition contract (compose at the call site, do not mutate via repeated calls).
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
        // "never set" is the way to express "no scoping". Explicitly setting an empty universe
        // is forbidden so that the call itself carries semantic weight: present => scoping.
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
    fun `declaredScopedSchemas accepts an empty scope set as a full-schema alias`() {
        extension.declaredSchemaScopes(setOf("public"))
        extension.declaredScopedSchemas("FULL_ALIAS" to emptySet())

        val schemas = extension.schemaScoping.get().scopedSchemas
        assertTrue(schemas.containsKey("FULL_ALIAS"))
        assertEquals(emptySet(), schemas["FULL_ALIAS"])
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
        // Symmetric with `declaredSchemaScopes` empty rejection: omitting the call is the way
        // to express "no scoped schemas declared".
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
        extension.declaredSchemaScopes(setOf("public", "internal"))
        val first = extension.schemaScoping.get()

        extension.declaredScopedSchemas("PUBLIC_API" to setOf("public"))
        val second = extension.schemaScoping.get()

        // First snapshot retains the state it captured: scoped-schemas map still empty.
        assertEquals(setOf("public", "internal"), first.scopeUniverse)
        assertEquals(emptyMap(), first.scopedSchemas)

        // Second snapshot reflects the additional declaration.
        assertEquals(setOf("public", "internal"), second.scopeUniverse)
        assertEquals(mapOf("PUBLIC_API" to setOf("public")), second.scopedSchemas)
    }

    @Test
    fun `pre-existing scalar properties remain functional`() {
        // Guard against accidental regression of the existing extension surface.
        extension.modulePackagePrefix.set("com.example")
        assertEquals("com.example", extension.modulePackagePrefix.get())
    }

    @Test
    fun `declaredSchemaScopes rejects a malformed scope id at setter time`() {
        val ex = assertThrows<GradleException> {
            extension.declaredSchemaScopes(setOf("public", "BAD-ID"))
        }
        assertTrue(
            ex.message!!.contains("SCOPE_ID_FORMAT_INVALID") && ex.message!!.contains("BAD-ID"),
            "Expected a coded, id-naming message, got: ${ex.message}",
        )
    }

    @Test
    fun `declaredScopedSchemas rejects a malformed scoped-schema id`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchemas("PUBLIC-API" to setOf("public"))
        }
        assertTrue(
            ex.message!!.contains("SCHEMA_ID_FORMAT_INVALID") && ex.message!!.contains("PUBLIC-API"),
            "Expected a coded, id-naming message, got: ${ex.message}",
        )
    }

    @Test
    fun `declaredScopedSchemas rejects a reserved scoped-schema id`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchemas("FULL" to setOf("public"))
        }
        assertTrue(
            ex.message!!.contains("SCHEMA_ID_RESERVED"),
            "Expected the reserved-id code, got: ${ex.message}",
        )
    }

    @Test
    fun `declaredScopedSchemas rejects a malformed scope id inside an entry`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredScopedSchemas("PUBLIC_API" to setOf("BAD-SCOPE"))
        }
        assertTrue(
            ex.message!!.contains("SCOPE_ID_FORMAT_INVALID") && ex.message!!.contains("BAD-SCOPE"),
            "Expected the scope-id-format code naming the bad scope, got: ${ex.message}",
        )
    }

    @Test
    fun `retrofitted slice-1 throws now carry an error code prefix`() {
        extension.declaredSchemaScopes(setOf("public"))
        val ex = assertThrows<GradleException> {
            extension.declaredSchemaScopes(setOf("internal"))
        }
        // The code prefix is added without dropping the original human-readable guidance.
        assertTrue(
            ex.message!!.contains("SCHEMA_SCOPES_DECLARED_TWICE") && ex.message!!.contains("only be called once"),
            "Expected both the code and the original guidance, got: ${ex.message}",
        )
    }

    @Test
    fun `assembled scoping matches a directly-constructed SchemaScoping`() {
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
}
