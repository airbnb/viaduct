package viaduct.service.api.scoping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class SchemaScopingValidatorTest {
    @Test
    fun `validateScopeId accepts identifier shapes`() {
        listOf("public", "internal_v2", "admin", "a", "a0", "scope_with_underscores").forEach { id ->
            assertNull(SchemaScopingValidator.validateScopeId(id), "expected '$id' to be accepted")
        }
    }

    @Test
    fun `validateScopeId rejects malformed identifiers`() {
        val rejected = listOf("", "Public", "INTERNAL", "1scope", "_leading", "with-hyphen", "with space", "tail-")
        rejected.forEach { id ->
            val err = SchemaScopingValidator.validateScopeId(id)
            assertNotNull(err, "expected '$id' to be rejected")
            assertEquals(ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID, err!!.code)
            assertTrue(err.message.contains("'$id'"), "expected message to quote the offending id, got: ${err.message}")
        }
    }

    @Test
    fun `validateScopeId rejects the asterisk wildcard`() {
        // The DSL never accepts the SDL-only "*" sentinel as a declared scope id.
        val err = SchemaScopingValidator.validateScopeId("*")
        assertNotNull(err)
        assertEquals(ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID, err!!.code)
    }

    @Test
    fun `validateSchemaId accepts the documented identifier styles`() {
        listOf("PUBLIC_API", "publicApi", "FullApi", "legacy_internal_api", "A", "a", "A0_b1").forEach { id ->
            assertNull(SchemaScopingValidator.validateSchemaId(id), "expected '$id' to be accepted")
        }
    }

    @Test
    fun `validateSchemaId rejects malformed identifiers`() {
        val rejected = listOf("", "1Public", "_PublicApi", "PUBLIC-API", "public api", "kebab-case")
        rejected.forEach { id ->
            val err = SchemaScopingValidator.validateSchemaId(id)
            assertNotNull(err, "expected '$id' to be rejected")
            assertEquals(ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID, err!!.code)
        }
    }

    @Test
    fun `validateSchemaId rejects reserved ids ahead of format`() {
        SchemaScopingValidator.RESERVED_SCHEMA_IDS.forEach { id ->
            val err = SchemaScopingValidator.validateSchemaId(id)
            assertNotNull(err)
            assertEquals(ScopingErrorCodes.SCHEMA_ID_RESERVED, err!!.code)
        }
    }

    @Test
    fun `validate accepts an empty scoping`() {
        assertEquals(emptyList<SchemaScopingValidationError>(), SchemaScopingValidator.validate(SchemaScoping.EMPTY))
    }

    @Test
    fun `validate accepts a universe-only scoping with no scoped schemas`() {
        // Declaring only the universe is a legitimate state — scoped-schema declarations may be
        // contributed later or omitted entirely.
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = emptyMap(),
        )
        assertEquals(emptyList<SchemaScopingValidationError>(), SchemaScopingValidator.validate(scoping))
    }

    @Test
    fun `validate accepts a sole base-alias entry when a universe is declared`() {
        // An entry like `"BASE_ALIAS" to emptySet()` is a documented base-schema alias:
        // the scoped schema exposes the base schema without additional scope filtering.
        // Distinct from the no-universe case below: the universe declaration is what flips
        // an empty-set entry from "invalid scoping intent" to "base-schema alias".
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("BASE_ALIAS" to emptySet()),
        )
        assertEquals(emptyList<SchemaScopingValidationError>(), SchemaScopingValidator.validate(scoping))
    }

    @Test
    fun `validate accepts a base-alias entry alongside a normal scoped schema`() {
        // Pins that the empty-set "base-schema alias" entry does not taint validation of its
        // siblings — the loop body's empty-unknown short-circuit must not interfere with
        // subset-checking the next entry.
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf(
                "BASE_ALIAS" to emptySet(),
                "PUBLIC_ONLY" to setOf("public"),
            ),
        )
        assertEquals(emptyList<SchemaScopingValidationError>(), SchemaScopingValidator.validate(scoping))
    }

    @Test
    fun `validate flags scoped schemas declared without a universe`() {
        val scoping = SchemaScoping(
            scopeUniverse = emptySet(),
            scopedSchemas = mapOf("API" to setOf("public")),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(1, errors.size)
        assertEquals(ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE, errors[0].code)
    }

    @Test
    fun `validate flags scoped schemas with empty scope sets declared without a universe`() {
        // An entry like `"API" to emptySet()` still counts as a declaration that implies scoping
        // intent: without a universe, the declaration is rejected.
        val scoping = SchemaScoping(
            scopeUniverse = emptySet(),
            scopedSchemas = mapOf("API" to emptySet()),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(1, errors.size)
        assertEquals(ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE, errors[0].code)
    }

    @Test
    fun `validate flags unknown scope references with offending ids in the message`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("API" to setOf("public", "secret", "missing")),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(1, errors.size)
        val err = errors.single()
        assertEquals(ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE, err.code)
        assertTrue(err.message.contains("'API'"))
        assertTrue(err.message.contains("missing"))
        assertTrue(err.message.contains("secret"))
    }

    @Test
    fun `validate aggregates errors across multiple scoped schemas in deterministic order`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = linkedMapOf(
                "Zeta" to setOf("missing_z"),
                "Alpha" to setOf("missing_a"),
            ),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        assertEquals(2, errors.size)
        // Output ordering is sorted by schema id for stable diagnostics.
        assertTrue(errors[0].message.contains("'Alpha'"))
        assertTrue(errors[1].message.contains("'Zeta'"))
    }

    @Test
    fun `validate combines the no-universe flag with subset violations`() {
        val scoping = SchemaScoping(
            scopeUniverse = emptySet(),
            scopedSchemas = mapOf("API" to setOf("public")),
        )
        val errors = SchemaScopingValidator.validate(scoping)
        // Currently only no-universe fires for this state because subset is meaningless without a
        // universe to compare against; the test pins the behavior so future changes are deliberate.
        assertEquals(1, errors.size)
        assertEquals(ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE, errors.single().code)
    }
}
