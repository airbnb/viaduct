package viaduct.gradle.task

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.scopes.ResourceFileSchema
import viaduct.graphql.schema.scopes.ScopedMode

/**
 * Unit tests for scope resource file generation.
 */
class ScopeResourceFileTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeResource(scopedMode: ScopedMode): java.io.File {
        val outputDir = tempDir.toFile()
        writeScopeResourceFile(outputDir, scopedMode)
        return outputDir.resolve("META-INF/viaduct/schema-scoping.json")
    }

    // resource file is well-formed JSON with required fields
    @Test
    fun `resource file is well-formed JSON with required top-level fields`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("api" to setOf("public")),
        )
        val file = writeResource(mode)

        assertTrue(file.exists())
        val mapper = ObjectMapper()
        val tree = mapper.readTree(file.readText())
        assertNotNull(tree.get("version"), "version field must be present")
        assertNotNull(tree.get("declaredSchemaScopes"), "declaredSchemaScopes field must be present")
        assertNotNull(tree.get("declaredScopedSchemas"), "declaredScopedSchemas field must be present")
    }

    // version field is present and non-empty
    @Test
    fun `version field is present and non-empty in serialized output`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("api" to setOf("public")),
        )
        val file = writeResource(mode)

        val mapper = ObjectMapper()
        val tree = mapper.readTree(file.readText())
        val version = tree.get("version").asText()
        assertTrue(version.isNotBlank(), "version must be non-empty: '$version'")
    }

    // FULL schema ID with empty scope set is present by default
    @Test
    fun `FULL schema ID with empty scope set is injected by default`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("api" to setOf("public")),
        )
        val file = writeResource(mode)

        val mapper = ObjectMapper()
        val tree = mapper.readTree(file.readText())
        val scopedSchemas = tree.get("declaredScopedSchemas")
        assertNotNull(scopedSchemas.get("FULL"), "FULL key must be present in declaredScopedSchemas")
        assertTrue(
            scopedSchemas.get("FULL").isArray && scopedSchemas.get("FULL").size() == 0,
            "FULL must map to an empty array"
        )
    }

    // assert FULL is present, not merely "no exception thrown"
    @Test
    fun `FULL schema ID is explicitly present even when not declared in scopedSchemas`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public"),
            scopedSchemas = emptyMap(),
        )
        val file = writeResource(mode)

        val json = file.readText()
        assertTrue(json.contains("\"FULL\""), "JSON must explicitly contain \"FULL\" key")
    }

    // sorted keys in JSON
    @Test
    fun `top-level keys in JSON are sorted alphabetically`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("api" to setOf("public")),
        )
        val json = writeResource(mode).readText()

        val declaredScopeIdx = json.indexOf("\"declaredSchemaScopes\"")
        val declaredSchemasIdx = json.indexOf("\"declaredScopedSchemas\"")
        val versionIdx = json.indexOf("\"version\"")

        assertTrue(declaredScopeIdx > 0, "declaredSchemaScopes must appear in JSON")
        assertTrue(declaredScopeIdx < declaredSchemasIdx, "declaredSchemaScopes before declaredScopedSchemas")
        assertTrue(declaredSchemasIdx < versionIdx, "declaredScopedSchemas before version")
    }

    // scope sets are sorted alphabetically within declaredSchemaScopes
    @Test
    fun `declaredSchemaScopes array is sorted alphabetically`() {
        // Deliberately unsorted input
        val mode = ScopedMode(
            scopeUniverse = setOf("zebra", "alpha", "monkey"),
            scopedSchemas = emptyMap(),
        )
        val json = writeResource(mode).readText()

        val alphaIdx = json.indexOf("\"alpha\"")
        val monkeyIdx = json.indexOf("\"monkey\"")
        val zebraIdx = json.indexOf("\"zebra\"")

        assertTrue(alphaIdx < monkeyIdx, "alpha before monkey")
        assertTrue(monkeyIdx < zebraIdx, "monkey before zebra")
    }

    // schema IDs in declaredScopedSchemas are sorted alphabetically
    @Test
    fun `declaredScopedSchemas keys are sorted alphabetically`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf(
                "zebra" to setOf("public"),
                "alpha" to setOf("internal"),
                "monkey" to setOf("public"),
            ),
        )
        val json = writeResource(mode).readText()

        val alphaIdx = json.indexOf("\"alpha\"")
        val monkeyIdx = json.indexOf("\"monkey\"")
        val zebraIdx = json.indexOf("\"zebra\"")

        assertTrue(alphaIdx < monkeyIdx, "alpha before monkey in schema keys")
        assertTrue(monkeyIdx < zebraIdx, "monkey before zebra in schema keys")
    }

    // deterministic byte-identical output for same inputs
    @Test
    fun `two invocations with same inputs produce byte-identical JSON`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf(
                "api" to setOf("public"),
                "admin" to setOf("public", "internal"),
            ),
        )

        val dir1 = tempDir.resolve("run1").toFile().also { it.mkdirs() }
        val dir2 = tempDir.resolve("run2").toFile().also { it.mkdirs() }

        writeScopeResourceFile(dir1, mode)
        writeScopeResourceFile(dir2, mode)

        val json1 = dir1.resolve("META-INF/viaduct/schema-scoping.json").readText()
        val json2 = dir2.resolve("META-INF/viaduct/schema-scoping.json").readText()

        assertEquals(json1, json2, "Byte-identical output required for same inputs")
    }

    // Sanity: file is written at the expected path
    @Test
    fun `resource file is written to META-INF-viaduct-schema-scoping dot json`() {
        val mode = ScopedMode(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("api" to setOf("public")),
        )
        writeResource(mode)

        assertTrue(tempDir.resolve("META-INF/viaduct/schema-scoping.json").toFile().exists())
    }
}
