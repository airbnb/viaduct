package viaduct.service.api.scoping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class SchemaScopingValidatorTest {
    /** One ID per shape the grammar has to admit; every other ID in use repeats one of these. */
    private val scopeIdShapes = listOf(
        "viaduct", // no separator
        "viaduct:public", // namespace segment
        "viaduct:internal-tools", // hyphen in the second segment
        "viaduct:__generated-types", // leading underscores after the colon
        "listing-block", // hyphen in the first segment
        "listing-block:private", // hyphen plus namespace segment
        "multi-hyphen-scope", // repeated hyphens
    )

    @Test
    fun `validateScopeId accepts every shape the grammar must admit`() {
        scopeIdShapes.forEach { id ->
            assertNull(SchemaScopingValidator.validateScopeId(id), "expected '$id' to be accepted")
        }
    }

    @Test
    fun `validateScopeId accepts identifier shapes`() {
        // Uppercase and hyphenated IDs are accepted: real @scope(to: [...]) values take both, and the
        // OSS tree's own published test fixtures use ADMIN / SCOPE1 / publicScope.
        val accepted = listOf(
            "public",
            "internal_v2",
            "admin",
            "a",
            "a0",
            "scope_with_underscores",
            "Public",
            "INTERNAL",
            "publicScope",
            "SCOPE1",
            "with-hyphen",
            "tail-",
        )
        accepted.forEach { id ->
            assertNull(SchemaScopingValidator.validateScopeId(id), "expected '$id' to be accepted")
        }
    }

    @Test
    fun `validateScopeId rejects malformed identifiers`() {
        val rejected = listOf("", "1scope", "_leading", "with space", "a:b:c", "trailing:", ":leading")
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
    fun `validate does not check whether a scope set is empty`() {
        // Subtracting the universe from an empty set leaves nothing to report, so this passes here.
        // An empty scope set is invalid, and SchemaScopeDefinitions is what rejects it — pinned so a
        // future change to move the rule down here is a deliberate one.
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("EMPTY_SET" to emptySet()),
        )
        assertEquals(emptyList<SchemaScopingValidationError>(), SchemaScopingValidator.validate(scoping))
    }

    @Test
    fun `validate subset-checks every entry regardless of its siblings`() {
        // The loop body's empty-unknown short-circuit must not interfere with the next entry.
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf(
                "EMPTY_SET" to emptySet(),
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
    fun `validate does not expand a namespace into its private scope, and says so`() {
        // ScopeDirectivesRule treats @scope(to: ["listing-block"]) as also granting
        // "listing-block:private"; schemaScopes requires it to be declared in its own right.
        val scoping = SchemaScoping(
            scopeUniverse = setOf("listing-block"),
            scopedSchemas = mapOf("PRIVATE_API" to setOf("listing-block:private")),
        )
        val err = SchemaScopingValidator.validate(scoping).single()
        assertEquals(ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE, err.code)
        assertTrue(err.message.contains(":private"), err.message)
        assertTrue(err.message.contains("@scope"), err.message)
    }

    @Test
    fun `validate omits the private-scope hint for an ordinary unknown scope`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("API" to setOf("typo")),
        )
        val err = SchemaScopingValidator.validate(scoping).single()
        assertFalse(err.message.contains("@scope"), err.message)
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
