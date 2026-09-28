package viaduct.service.api.scoping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Covers the schema scope definition contract: one negative fixture per rule the decoder owns, plus the
 * states a valid file can be in.
 *
 * Inputs are the generic maps a YAML parser produces, which is the whole point of the split — these
 * rules are testable, and reusable by another build tool, without a YAML dependency.
 */
@OptIn(ExperimentalApi::class, InternalApi::class)
class SchemaScopeDefinitionsTest {
    private fun decode(vararg pairs: Pair<String, Any?>): SchemaScopeDefinitions.Result = SchemaScopeDefinitions.fromDecoded(mapOf(*pairs))

    private fun succeeds(vararg pairs: Pair<String, Any?>): SchemaScoping {
        val result = decode(*pairs)
        assertTrue(result is SchemaScopeDefinitions.Result.Success, "expected success, got $result")
        return (result as SchemaScopeDefinitions.Result.Success).scoping
    }

    private fun failsWith(
        code: String,
        vararg pairs: Pair<String, Any?>,
    ): SchemaScopingValidationError {
        val result = decode(*pairs)
        assertTrue(result is SchemaScopeDefinitions.Result.Failure, "expected failure, got $result")
        val errors = (result as SchemaScopeDefinitions.Result.Failure).errors
        val match = errors.firstOrNull { it.code == code }
        assertTrue(match != null, "expected a $code finding, got ${errors.map { it.code }}")
        return match!!
    }

    private fun entry(
        id: String,
        scopes: Any?,
    ): Map<String, Any?> = mapOf("id" to id, "scopes" to scopes)

    @Test
    fun `an absent file decodes to no scoping`() {
        assertEquals(
            SchemaScoping.EMPTY,
            (SchemaScopeDefinitions.fromDecoded(null) as SchemaScopeDefinitions.Result.Success).scoping,
        )
    }

    @Test
    fun `an empty document decodes to no scoping`() {
        assertEquals(SchemaScoping.EMPTY, succeeds())
    }

    @Test
    fun `a version-only file is valid and unscoped`() {
        val scoping = succeeds("version" to 1)
        assertEquals(SchemaScoping.EMPTY, scoping)
        assertEquals(false, scoping.isScoped)
    }

    @Test
    fun `a universe without named entries is valid`() {
        val scoping = succeeds("version" to 1, "schemaScopes" to listOf("public", "internal"))
        assertEquals(setOf("public", "internal"), scoping.scopeUniverse)
        assertEquals(emptyMap<String, Set<String>>(), scoping.scopedSchemas)
        assertEquals(true, scoping.isScoped)
    }

    @Test
    fun `a universe with named entries round-trips id for id and scope set for scope set`() {
        val scoping = succeeds(
            "version" to 1,
            "schemaScopes" to listOf("viaduct:public", "viaduct:internal-tools", "listing-block"),
            "scopedSchemas" to listOf(
                entry("PUBLIC_API", listOf("viaduct:public")),
                entry("INTERNAL_TOOLS", listOf("viaduct:public", "viaduct:internal-tools")),
            ),
        )
        assertEquals(setOf("viaduct:public", "viaduct:internal-tools", "listing-block"), scoping.scopeUniverse)
        assertEquals(
            mapOf(
                "PUBLIC_API" to setOf("viaduct:public"),
                "INTERNAL_TOOLS" to setOf("viaduct:public", "viaduct:internal-tools"),
            ),
            scoping.scopedSchemas,
        )
    }

    @Test
    fun `version accepts the YAML integer and the quoted string alike`() {
        // YAML decodes `version: 1` as an Int and `version: "1"` as a String, and the supported value is
        // compared as a String, so both have to be accepted.
        succeeds("version" to 1)
        succeeds("version" to "1")
    }

    @Test
    fun `a version key with no value is rejected as missing rather than unsupported`() {
        val error = failsWith(
            ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING,
            "version" to null,
            "schemaScopes" to listOf("public"),
        )
        assertFalse(error.message.contains("null"), error.message)
    }

    @Test
    fun `an unsupported version is rejected`() {
        val error = failsWith(ScopingErrorCodes.SCOPES_FILE_VERSION_UNSUPPORTED, "version" to 2)
        assertTrue(error.message.contains("2"))
    }

    @Test
    fun `a document declaring definitions without a version is rejected`() {
        val error = failsWith(ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING, "schemaScopes" to listOf("public"))
        assertTrue(error.message.contains("version: 1"), error.message)
    }

    @Test
    fun `a missing version suppresses the content checks`() {
        // Version gates the rest: a later release must not read this file as a format it predates.
        val result = decode("schemaScopes" to listOf("1bad"))
        val errors = (result as SchemaScopeDefinitions.Result.Failure).errors
        assertEquals(listOf(ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING), errors.map { it.code })
    }

    @Test
    fun `a missing version is reported alongside sibling shape findings`() {
        // It is a shape-stage check, so it does not suppress its own stage.
        val result = decode("typo" to 1, "schemaScopes" to "public")
        val errors = (result as SchemaScopeDefinitions.Result.Failure).errors
        assertEquals(
            listOf(
                ScopingErrorCodes.SCOPES_FILE_UNKNOWN_KEY,
                ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING,
                ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            ),
            errors.map { it.code },
        )
    }

    @Test
    fun `an unknown top-level key is rejected`() {
        val error = failsWith(
            ScopingErrorCodes.SCOPES_FILE_UNKNOWN_KEY,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "schemaScope" to listOf("public"),
        )
        assertTrue(error.message.contains("schemaScope"), error.message)
    }

    @Test
    fun `an unknown key inside a scopedSchemas entry is rejected`() {
        val error = failsWith(
            ScopingErrorCodes.SCOPES_FILE_UNKNOWN_KEY,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(mapOf("id" to "PUBLIC_API", "scopes" to listOf("public"), "publish" to true)),
        )
        assertTrue(error.message.contains("publish"), error.message)
        assertTrue(error.message.contains("scopedSchemas[0]"), error.message)
    }

    @Test
    fun `schemaScopes holding something other than a list is rejected`() {
        failsWith(ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE, "version" to 1, "schemaScopes" to "public")
    }

    @Test
    fun `scopedSchemas holding something other than a list is rejected`() {
        failsWith(
            ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to mapOf("PUBLIC_API" to listOf("public")),
        )
    }

    @Test
    fun `a scopedSchemas element that is not an object is rejected`() {
        failsWith(
            ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf("PUBLIC_API"),
        )
    }

    @Test
    fun `a non-string scope ID element is rejected`() {
        val error = failsWith(ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE, "version" to 1, "schemaScopes" to listOf(7))
        assertTrue(error.message.contains("schemaScopes[0]"), error.message)
    }

    @Test
    fun `a scope ID written with a space after the colon is rejected with the YAML mapping hint`() {
        // `- viaduct: public` is a YAML mapping, not the string "viaduct:public" — a likely typo in a
        // file whose whole purpose is holding colon-namespaced IDs.
        val error = failsWith(
            ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            "version" to 1,
            "schemaScopes" to listOf(mapOf("viaduct" to "public")),
        )
        assertTrue(error.message.contains("viaduct:public"), error.message)
        assertTrue(error.message.contains("no space after the colon"), error.message)
    }

    @Test
    fun `an empty mapping element is rejected without inventing a colon hint`() {
        val error = failsWith(
            ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            "version" to 1,
            "schemaScopes" to listOf(emptyMap<String, Any?>()),
        )
        assertTrue(error.message.contains("must be a scope ID"), error.message)
        assertFalse(error.message.contains("null"), error.message)
    }

    @Test
    fun `a scope id left with a trailing colon is told to drop the colon`() {
        // `- viaduct:` decodes to {viaduct=null}, where the fix is `- viaduct` rather than the
        // no-space-after-the-colon advice the populated case gets.
        val error = failsWith(
            ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            "version" to 1,
            "schemaScopes" to listOf(mapOf("viaduct" to null)),
        )
        assertTrue(error.message.contains("'- viaduct'"), error.message)
        assertFalse(error.message.contains("null"), error.message)
    }

    @Test
    fun `an entry missing its id is rejected`() {
        failsWith(
            ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(mapOf("scopes" to listOf("public"))),
        )
    }

    @Test
    fun `a declared but empty schemaScopes list is rejected`() {
        val error = failsWith(
            ScopingErrorCodes.SCHEMA_SCOPES_EMPTY,
            "version" to 1,
            "schemaScopes" to emptyList<String>(),
        )
        assertTrue(error.message.contains("Omit the key"), error.message)
    }

    @Test
    fun `a schemaScopes key with no value is rejected as empty rather than treated as omitted`() {
        failsWith(ScopingErrorCodes.SCHEMA_SCOPES_EMPTY, "version" to 1, "schemaScopes" to null)
    }

    @Test
    fun `a duplicate scope ID in schemaScopes is rejected`() {
        val error = failsWith(
            ScopingErrorCodes.SCHEMA_SCOPE_DUPLICATE_ID,
            "version" to 1,
            "schemaScopes" to listOf("public", "internal", "public"),
        )
        assertTrue(error.message.contains("public"), error.message)
    }

    @Test
    fun `a duplicate scopedSchemas id is rejected rather than silently collapsed`() {
        // The list form exists so this is our own check: a map keyed by id would have dropped one entry.
        val error = failsWith(
            ScopingErrorCodes.SCOPED_SCHEMA_DUPLICATE_ID,
            "version" to 1,
            "schemaScopes" to listOf("public", "internal"),
            "scopedSchemas" to listOf(
                entry("PUBLIC_API", listOf("public")),
                entry("PUBLIC_API", listOf("internal")),
            ),
        )
        assertTrue(error.message.contains("PUBLIC_API"), error.message)
    }

    @Test
    fun `a malformed scope ID is rejected`() {
        failsWith(
            ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID,
            "version" to 1,
            "schemaScopes" to listOf("public", "1bad"),
        )
    }

    @Test
    fun `the asterisk wildcard is not declarable`() {
        failsWith(ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID, "version" to 1, "schemaScopes" to listOf("*"))
    }

    @Test
    fun `a malformed scoped-schema id is rejected`() {
        failsWith(
            ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(entry("kebab-case", listOf("public"))),
        )
    }

    @Test
    fun `reserved scoped-schema ids cannot be declared`() {
        SchemaScopingValidator.RESERVED_SCHEMA_IDS.forEach { reserved ->
            failsWith(
                ScopingErrorCodes.SCHEMA_ID_RESERVED,
                "version" to 1,
                "schemaScopes" to listOf("public"),
                "scopedSchemas" to listOf(entry(reserved, listOf("public"))),
            )
        }
    }

    @Test
    fun `an entry selecting no scopes is rejected`() {
        // SchemaScopingValidator.validate accepts this, so the rule has to live in this layer.
        val declared = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("PUBLIC_API" to emptySet()),
        )
        assertEquals(emptyList<SchemaScopingValidationError>(), SchemaScopingValidator.validate(declared))

        val error = failsWith(
            ScopingErrorCodes.SCOPED_SCHEMA_EMPTY_SCOPES,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(entry("PUBLIC_API", emptyList<String>())),
        )
        assertTrue(error.message.contains(SchemaScopingValidator.BASE_SCHEMA_ID), error.message)
    }

    @Test
    fun `an entry whose scopes key has no value is rejected as empty`() {
        failsWith(
            ScopingErrorCodes.SCOPED_SCHEMA_EMPTY_SCOPES,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(entry("PUBLIC_API", null)),
        )
    }

    @Test
    fun `named entries without a declared universe are rejected`() {
        failsWith(
            ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE,
            "version" to 1,
            "scopedSchemas" to listOf(entry("PUBLIC_API", listOf("public"))),
        )
    }

    @Test
    fun `a scope set outside the declared universe is rejected`() {
        val error = failsWith(
            ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE,
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(entry("PUBLIC_API", listOf("public", "secret"))),
        )
        assertTrue(error.message.contains("secret"), error.message)
    }

    @Test
    fun `shape findings are reported together and suppress content checks`() {
        // Two independent shape problems in one file, and the malformed scope ID inside the unusable
        // value is not reported on top of them.
        val result = decode(
            "version" to 1,
            "typo" to 1,
            "schemaScopes" to "public",
        )
        val errors = (result as SchemaScopeDefinitions.Result.Failure).errors
        assertEquals(
            listOf(ScopingErrorCodes.SCOPES_FILE_UNKNOWN_KEY, ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE),
            errors.map { it.code },
        )
    }

    @Test
    fun `content findings are aggregated across entries in document order`() {
        val result = decode(
            "version" to 1,
            "schemaScopes" to listOf("public"),
            "scopedSchemas" to listOf(
                entry("1bad", listOf("public")),
                entry("AlsoBad", emptyList<String>()),
            ),
        )
        val errors = (result as SchemaScopeDefinitions.Result.Failure).errors
        assertEquals(
            listOf(ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID, ScopingErrorCodes.SCOPED_SCHEMA_EMPTY_SCOPES),
            errors.map { it.code },
        )
    }
}
