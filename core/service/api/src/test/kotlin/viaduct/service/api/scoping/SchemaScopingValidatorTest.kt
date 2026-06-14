package viaduct.service.api.scoping

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi

/**
 * Unit tests for the pure schema-scoping validation logic. No Gradle: these exercise
 * [SchemaScopingValidator]'s per-ID syntax checks and cross-property invariants directly, asserting
 * on returned error codes rather than on thrown `GradleException`s (which the plugin layer renders).
 */
@OptIn(ExperimentalApi::class)
class SchemaScopingValidatorTest {
    // ── validateScopeId ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `validateScopeId accepts GraphQL-identifier-shaped ids`() {
        assertNull(SchemaScopingValidator.validateScopeId("public"))
        assertNull(SchemaScopingValidator.validateScopeId("internal_v2"))
        assertNull(SchemaScopingValidator.validateScopeId("a"))
    }

    @Test
    fun `validateScopeId rejects empty, uppercase, hyphenated, and leading-digit ids`() {
        listOf("", "Public", "bad-id", "2fast", "_leading").forEach { bad ->
            val error = SchemaScopingValidator.validateScopeId(bad)
            assertEquals(
                ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID,
                error?.code,
                "Expected '$bad' to be rejected as a malformed scope id",
            )
            assertTrue(
                error!!.message.contains("'$bad'"),
                "Expected message to name the offending id, got: ${error.message}",
            )
        }
    }

    // ── validateSchemaId ────────────────────────────────────────────────────────────────────────

    @Test
    fun `validateSchemaId accepts upper, lower, and mixed-case ids`() {
        assertNull(SchemaScopingValidator.validateSchemaId("PUBLIC_API"))
        assertNull(SchemaScopingValidator.validateSchemaId("publicApi"))
        assertNull(SchemaScopingValidator.validateSchemaId("FullApi"))
    }

    @Test
    fun `validateSchemaId rejects hyphenated, leading-digit, and leading-underscore ids`() {
        listOf("PUBLIC-API", "2API", "_API").forEach { bad ->
            assertEquals(
                ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID,
                SchemaScopingValidator.validateSchemaId(bad)?.code,
                "Expected '$bad' to be rejected as a malformed scoped-schema id",
            )
        }
    }

    @Test
    fun `validateSchemaId rejects reserved FULL and NONE`() {
        SchemaScopingValidator.RESERVED_SCHEMA_IDS.forEach { reserved ->
            val error = SchemaScopingValidator.validateSchemaId(reserved)
            assertEquals(
                ScopingErrorCodes.SCHEMA_ID_RESERVED,
                error?.code,
                "Expected reserved id '$reserved' to be rejected",
            )
        }
        // The reserved-name check must take precedence over the (otherwise-passing) syntax check.
        assertEquals(setOf("FULL", "NONE"), SchemaScopingValidator.RESERVED_SCHEMA_IDS)
    }

    // ── validate (cross-property) ───────────────────────────────────────────────────────────────

    @Test
    fun `validate of EMPTY yields no errors`() {
        assertTrue(SchemaScopingValidator.validate(SchemaScoping.EMPTY).isEmpty())
    }

    @Test
    fun `validate accepts a declared universe with no scoped schemas`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = emptyMap(),
        )
        assertTrue(
            SchemaScopingValidator.validate(scoping).isEmpty(),
            "A universe with no scoped schemas is valid — extra unused scopes are allowed",
        )
    }

    @Test
    fun `validate accepts a full-schema alias whose scope set is empty`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("FULL_ALIAS" to emptySet()),
        )
        assertTrue(SchemaScopingValidator.validate(scoping).isEmpty())
    }

    @Test
    fun `validate flags a subset violation and names the offending scopes`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("PUBLIC_API" to setOf("public", "admin")),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(1, errors.size)
        assertEquals(ScopingErrorCodes.SCOPE_SET_NOT_SUBSET, errors.single().code)
        assertTrue(
            errors.single().message.contains("PUBLIC_API") && errors.single().message.contains("admin"),
            "Expected message to name the schema and the undeclared scope, got: ${errors.single().message}",
        )
    }

    @Test
    fun `validate flags scoped schemas declared without a universe`() {
        val scoping = SchemaScoping(
            scopeUniverse = emptySet(),
            scopedSchemas = mapOf("FULL_ALIAS" to emptySet()),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(
            listOf(ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE),
            errors.map { it.code },
            "An empty-scope-set alias still requires a declared universe",
        )
    }

    @Test
    fun `validate aggregates multiple violations`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf(
                "PUBLIC_API" to setOf("public"), // valid
                "ADMIN_API" to setOf("admin"), // subset violation
                "OPS_API" to setOf("ops"), // subset violation
            ),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.code == ScopingErrorCodes.SCOPE_SET_NOT_SUBSET })
    }
}
