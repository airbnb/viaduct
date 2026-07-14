package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.service.api.scoping.SchemaScoping
import viaduct.service.api.scoping.ScopingErrorCodes

/**
 * Tests for the schema-scoping DSL surface on ViaductApplicationExtension.
 *
 * The DSL is a single `declareScoping { ... }` block exposing `scopes(...)` and
 * `scopedSchema(...)` methods. The block may be called at most once, `scopes(...)` may be called
 * at most once inside it, and IDs are validated as they are declared. Per-ID format/reserved-name
 * coverage lives in [viaduct.service.api.scoping.SchemaScopingValidatorTest]; here we cover the
 * extension-layer DSL invariants and confirm the resulting `Provider<SchemaScoping>`.
 */
@OptIn(ExperimentalApi::class, InternalApi::class)
class ViaductApplicationExtensionTest {
    private lateinit var extension: ViaductApplicationExtension

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        extension = ViaductApplicationExtension(project.objects)
    }

    @Test
    fun `schemaScoping defaults to EMPTY when declareScoping is never called`() {
        assertEquals(SchemaScoping.EMPTY, extension.schemaScoping.get())
        assertFalse(extension.schemaScoping.get().isScoped)
    }

    @Test
    fun `declareScoping with scopes and scopedSchema populates the snapshot`() {
        extension.declareScoping {
            scopes("public", "internal", "admin")
            scopedSchema("BASE_ALIAS")
            scopedSchema("PUBLIC_API", "public")
            scopedSchema("INTERNAL_API", "public", "internal")
        }

        val expected = SchemaScoping(
            scopeUniverse = setOf("public", "internal", "admin"),
            scopedSchemas = mapOf(
                "BASE_ALIAS" to emptySet(),
                "PUBLIC_API" to setOf("public"),
                "INTERNAL_API" to setOf("public", "internal"),
            ),
        )
        assertEquals(expected, extension.schemaScoping.get())
        assertTrue(extension.schemaScoping.get().isScoped)
    }

    @Test
    fun `declareScoping rejects a second call with SCHEMA_SCOPING_DECLARED_TWICE`() {
        extension.declareScoping {
            scopes("public")
            scopedSchema("API", "public")
        }
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("internal")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCHEMA_SCOPING_DECLARED_TWICE
    }

    @Test
    fun `scopes rejects a second call within the same block with SCHEMA_SCOPES_DECLARED_TWICE`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public")
                scopes("internal")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCHEMA_SCOPES_DECLARED_TWICE
    }

    @Test
    fun `scopes with no arguments fails with SCHEMA_SCOPES_EMPTY`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes()
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCHEMA_SCOPES_EMPTY
    }

    @Test
    fun `scopes flags duplicate scope IDs in the vararg list with SCHEMA_SCOPE_DUPLICATE_ID`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public", "internal", "public")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCHEMA_SCOPE_DUPLICATE_ID
        ex.message!! shouldContain "public"
    }

    @Test
    fun `scopedSchema rejects duplicate IDs with SCOPED_SCHEMA_DUPLICATE_ID`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public", "internal")
                scopedSchema("PUBLIC_API", "public")
                scopedSchema("PUBLIC_API", "internal")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCOPED_SCHEMA_DUPLICATE_ID
        ex.message!! shouldContain "PUBLIC_API"
    }

    @Test
    fun `scopes flags malformed scope ID with SCOPE_ID_FORMAT_INVALID`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public", "BAD-ID")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID
        ex.message!! shouldContain "BAD-ID"
    }

    @Test
    fun `scopedSchema flags malformed schema ID with SCHEMA_ID_FORMAT_INVALID`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public")
                scopedSchema("kebab-case", "public")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID
    }

    @Test
    fun `scopedSchema flags reserved schema ID with SCHEMA_ID_RESERVED`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public")
                scopedSchema("BASE", "public")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCHEMA_ID_RESERVED
    }

    @Test
    fun `scopedSchema surfaces malformed scope ID even when the schema ID is a duplicate`() {
        // Regression guard for ordering: scope-id shape validation must run before the
        // duplicate-schema-id guard, so a copy-pasted line with a typo'd scope surfaces the
        // format error at the offending line instead of hiding behind DUPLICATE_ID.
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public")
                scopedSchema("API", "public")
                scopedSchema("API", "BAD-ID")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID
        ex.message!! shouldContain "BAD-ID"
    }

    @Test
    fun `build flags scoped schema referencing unknown scope with SCOPED_SCHEMA_UNKNOWN_SCOPE`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public")
                scopedSchema("API", "public", "missing")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE
        ex.message!! shouldContain "missing"
    }

    @Test
    fun `build flags scopedSchema without a declared universe with SCOPED_SCHEMAS_WITHOUT_UNIVERSE`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopedSchema("BASE_ALIAS")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE
    }

    @Test
    fun `declareScoping with universe only is valid`() {
        // Legitimate: declare the universe but no scoped schemas in this slice — scoped schemas
        // may be contributed later or omitted entirely.
        extension.declareScoping {
            scopes("public", "internal")
        }
        val snapshot = extension.schemaScoping.get()
        assertEquals(setOf("public", "internal"), snapshot.scopeUniverse)
        assertTrue(snapshot.scopedSchemas.isEmpty())
        assertTrue(snapshot.isScoped)
    }

    @Test
    fun `declareScoping aggregates multiple cross-property findings into one message`() {
        val ex = assertThrows<GradleException> {
            extension.declareScoping {
                scopes("public")
                scopedSchema("Alpha", "missing_a")
                scopedSchema("Beta", "missing_b")
            }
        }
        ex.message!! shouldContain ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE
        ex.message!! shouldContain "'Alpha'"
        ex.message!! shouldContain "'Beta'"
        ex.message!! shouldContain "missing_a"
        ex.message!! shouldContain "missing_b"
    }
}
