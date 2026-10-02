package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.service.api.scoping.SchemaScoping
import viaduct.service.api.scoping.ScopingErrorCodes

/**
 * Covers the YAML layer only: reading real files, and the failures that can only be detected while
 * parsing. Rule-by-rule coverage of the definitions themselves lives in
 * [viaduct.service.api.scoping.SchemaScopeDefinitionsTest], which needs no YAML.
 */
@OptIn(ExperimentalApi::class, InternalApi::class)
class ViaductScopesYamlTest {
    @TempDir
    lateinit var dir: File

    private fun write(content: String): File = File(dir, "scopes.yaml").apply { writeText(content) }

    private fun readFailure(content: String): GradleException = assertThrows<GradleException> { ViaductScopesYaml.read(write(content)) }

    @Test
    fun `the conventional path composes the shared file name`() {
        assertEquals("src/main/viaduct/scopes.yaml", ViaductScopesYaml.RELATIVE_PATH)
    }

    @Test
    fun `an absent file reads as no scoping`() {
        assertEquals(SchemaScoping.EMPTY, ViaductScopesYaml.read(File(dir, "scopes.yaml")))
        assertEquals(SchemaScoping.EMPTY, ViaductScopesYaml.read(null))
    }

    @Test
    fun `an empty file reads as no scoping`() {
        assertEquals(SchemaScoping.EMPTY, ViaductScopesYaml.read(write("")))
    }

    @Test
    fun `a comments-only file reads as no scoping`() {
        assertEquals(SchemaScoping.EMPTY, ViaductScopesYaml.read(write("# nothing declared yet\n")))
    }

    @Test
    fun `a valid file round-trips its definitions`() {
        val scoping = ViaductScopesYaml.read(
            write(
                """
                version: 1

                schemaScopes:
                  - viaduct:public
                  - viaduct:internal-tools
                  - listing-block

                scopedSchemas:
                  - id: PUBLIC_API
                    scopes: [viaduct:public]
                  - id: INTERNAL_TOOLS
                    scopes: [viaduct:public, viaduct:internal-tools]
                """.trimIndent()
            )
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
    fun `colon-namespaced ids survive parsing as plain strings`() {
        val scoping = ViaductScopesYaml.read(
            write(
                """
                version: 1

                schemaScopes:
                  - viaduct:__generated-types
                  - listing-block:private
                """.trimIndent()
            )
        )
        assertEquals(setOf("viaduct:__generated-types", "listing-block:private"), scoping.scopeUniverse)
    }

    @Test
    fun `a file declaring definitions without a version is rejected`() {
        val error = readFailure("schemaScopes:\n  - public\n")
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING
    }

    @Test
    fun `failure messages name the file with forward slashes on every platform`() {
        // The Windows nightly asserts on RELATIVE_PATH, which is always forward-slashed. Asserting a
        // literal keeps this from passing vacuously by reusing the production call.
        val nested = File(dir, "src/main/viaduct/scopes.yaml").apply {
            parentFile.mkdirs()
            writeText("schemaScopes: [public\n")
        }
        val error = assertThrows<GradleException> { ViaductScopesYaml.read(nested) }
        error.message!! shouldContain ViaductScopesYaml.RELATIVE_PATH
    }

    @Test
    fun `a duplicated top-level key is rejected rather than silently keeping the last`() {
        // YAML permits a repeated key and keeps the last value, so this can only be caught inside the
        // parser — by the time a map exists the first value is already gone.
        val error = readFailure(
            """
            schemaScopes:
              - public
            schemaScopes:
              - internal
            """.trimIndent()
        )
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
        error.message!! shouldContain "schemaScopes"
    }

    @Test
    fun `a duplicated key nested inside an entry is rejected`() {
        val error = readFailure(
            """
            schemaScopes:
              - public
            scopedSchemas:
              - id: PUBLIC_API
                id: OTHER_API
                scopes: [public]
            """.trimIndent()
        )
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
    }

    @Test
    fun `unparseable YAML is rejected with the file path`() {
        val error = readFailure("schemaScopes: [public\n")
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
        error.message!! shouldContain "scopes.yaml"
    }

    @Test
    fun `a second YAML document is rejected rather than ignored`() {
        // Only the first document is read, so without this the declared universe silently disappears
        // and the application builds unscoped.
        val error = readFailure("version: 1\n---\nversion: 1\nschemaScopes: [public]\n")
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
        error.message!! shouldContain "more than one YAML document"
    }

    @Test
    fun `a malformed second document is rejected rather than ignored`() {
        val error = readFailure("version: 1\nschemaScopes: [public]\n---\nnonsense: [\n")
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
    }

    @Test
    fun `a YAML alias is rejected because it resolves to the anchor name`() {
        // `*public` refers to the anchor on `internal`, but decodes as the string "public" — which is
        // itself declared here, so the scoped schema would validate while selecting the wrong scope.
        val error = readFailure(
            """
            version: 1
            schemaScopes: [&public internal, public]
            scopedSchemas:
              - id: API
                scopes: [*public]
            """.trimIndent()
        )
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
        error.message!! shouldContain "alias"
    }

    @Test
    fun `an undefined YAML alias is rejected rather than read as a scope id`() {
        val error = readFailure("version: 1\nschemaScopes: [*nosuch]\n")
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
    }

    @Test
    fun `an anchor with no alias referring to it is accepted`() {
        val scoping = ViaductScopesYaml.read(write("version: 1\nschemaScopes: [&public internal]\n"))
        assertEquals(setOf("internal"), scoping.scopeUniverse)
    }

    @Test
    fun `a document that is not a mapping is rejected`() {
        val error = readFailure("- public\n- internal\n")
        error.message!! shouldContain ScopingErrorCodes.SCOPES_FILE_MALFORMED
        error.message!! shouldContain "mapping"
    }

    @Test
    fun `definition findings are reported together with the file path and their codes`() {
        val error = readFailure(
            """
            version: 1
            schemaScopes:
              - public
            scopedSchemas:
              - id: kebab-case
                scopes: [public]
              - id: OTHER_API
                scopes: []
            """.trimIndent()
        )
        error.message!! shouldContain "scopes.yaml"
        error.message!! shouldContain ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID
        error.message!! shouldContain ScopingErrorCodes.SCOPED_SCHEMA_EMPTY_SCOPES
    }

    @Test
    fun `a scope outside the declared universe is rejected with the file path`() {
        val error = readFailure(
            """
            version: 1
            schemaScopes:
              - public
            scopedSchemas:
              - id: OTHER_API
                scopes: [missing]
            """.trimIndent()
        )
        error.message!! shouldContain "scopes.yaml"
        error.message!! shouldContain ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE
    }
}
