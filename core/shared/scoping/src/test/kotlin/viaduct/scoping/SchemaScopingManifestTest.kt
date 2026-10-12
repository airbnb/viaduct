package viaduct.scoping

import java.io.File
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/** Covers the manifest wire contract, which a second build tool has to be able to target. */
@OptIn(ExperimentalApi::class, InternalApi::class)
class SchemaScopingManifestTest {
    private val scoping = SchemaScoping(
        scopeUniverse = setOf("extras", "default"),
        scopedSchemas = mapOf(
            "publicSchemaWithExtras" to setOf("extras", "default"),
            "publicSchema" to setOf("default"),
        ),
    )

    @Test
    fun `definitions survive a round trip`() {
        assertEquals(scoping, parse(SchemaScopingManifest.encode(scoping)))
    }

    @Test
    fun `definitions with a universe and no named schemas survive a round trip`() {
        val universeOnly = SchemaScoping(scopeUniverse = setOf("default"), scopedSchemas = emptyMap())

        assertEquals(universeOnly, parse(SchemaScopingManifest.encode(universeOnly)))
    }

    @Test
    fun `the JSON field names are the ones a producer has to write`() {
        val json = SchemaScopingManifest.encode(scoping)

        assertEquals(
            """{"schemaScopes":["default","extras"],""" +
                """"scopedSchemas":[{"id":"publicSchema","scopes":["default"]},""" +
                """{"id":"publicSchemaWithExtras","scopes":["default","extras"]}],""" +
                """"version":"1"}""",
            json,
        )
    }

    @Test
    fun `equal definitions encode to identical bytes regardless of declaration order`() {
        val reordered = SchemaScoping(
            scopeUniverse = linkedSetOf("default", "extras"),
            scopedSchemas = linkedMapOf(
                "publicSchema" to linkedSetOf("default"),
                "publicSchemaWithExtras" to linkedSetOf("default", "extras"),
            ),
        )

        assertEquals(SchemaScopingManifest.encode(scoping), SchemaScopingManifest.encode(reordered))
    }

    @Test
    fun `an unsupported version is rejected and names the resource`() {
        val message = assertThrows<IllegalArgumentException> {
            parse("""{"schemaScopes":["default"],"version":"2"}""")
        }.message!!

        assertTrue(message.contains(SOURCE), message)
        assertTrue(message.contains(ScopingErrorCodes.SCOPES_FILE_VERSION_UNSUPPORTED), message)
    }

    @Test
    fun `a bare numeric version is accepted, as the YAML decoder accepts it`() {
        assertEquals(setOf("default"), parse("""{"schemaScopes":["default"],"version":1}""").scopeUniverse)
    }

    /**
     * The manifest is held to the rules that accepted the `scopes.yaml` it came from. Each case here is
     * unreachable through the Gradle producer and reachable for any other producer targeting this format.
     */
    @Test
    fun `producer errors the declaration decoder rejects are rejected here too`() {
        val cases = listOf(
            ScopingErrorCodes.SCOPED_SCHEMA_DUPLICATE_ID to
                """{"schemaScopes":["a","b"],"scopedSchemas":[{"id":"A","scopes":["a"]},{"id":"A","scopes":["b"]}],"version":"1"}""",
            ScopingErrorCodes.SCOPED_SCHEMA_EMPTY_SCOPES to
                """{"schemaScopes":["a"],"scopedSchemas":[{"id":"A","scopes":[]}],"version":"1"}""",
            ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID to
                """{"schemaScopes":["*"],"version":"1"}""",
            ScopingErrorCodes.SCHEMA_ID_RESERVED to
                """{"schemaScopes":["a"],"scopedSchemas":[{"id":"BASE","scopes":["a"]}],"version":"1"}""",
            ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING to
                """{"schemaScopes":["a"]}""",
            ScopingErrorCodes.SCOPES_FILE_UNKNOWN_KEY to
                """{"schemaScopes":["a"],"version":"1","future":true}""",
        )

        cases.forEach { (code, json) ->
            val message = assertThrows<IllegalArgumentException>(json) { parse(json) }.message!!

            assertTrue(message.contains(SOURCE), "$json\n$message")
            assertTrue(message.contains(code), "$json\n$message")
        }
    }

    @Test
    fun `a repeated key is rejected rather than resolved to its last occurrence`() {
        val cases = listOf(
            """{"schemaScopes":["a"],"schemaScopes":["b"],"version":"1"}""",
            """{"schemaScopes":["a","b"],"scopedSchemas":[{"id":"A","scopes":["a"],"scopes":["b"]}],"version":"1"}""",
        )

        cases.forEach { json ->
            val message = assertThrows<IllegalArgumentException>(json) { parse(json) }.message!!

            assertTrue(message.contains(SOURCE), "$json\n$message")
        }
    }

    @Test
    fun `content after the first object is rejected rather than ignored`() {
        val valid = SchemaScopingManifest.encode(scoping)

        val message = assertThrows<IllegalArgumentException> { parse("$valid$valid") }.message!!

        assertTrue(message.contains(SOURCE), message)
    }

    @Test
    fun `a manifest declaring nothing is rejected rather than read as unscoped`() {
        val message = assertThrows<IllegalArgumentException> { parse("{}") }.message!!

        assertTrue(message.contains(SOURCE), message)
        assertTrue(message.contains("declares no scope universe"), message)
    }

    @Test
    fun `unreadable content is rejected and names the resource`() {
        val message = assertThrows<IllegalArgumentException> { parse("not json") }.message!!

        assertTrue(message.contains(SOURCE), message)
    }

    @Test
    fun `definitions that contradict themselves are rejected and name the resource`() {
        val message = assertThrows<IllegalArgumentException> {
            parse("""{"schemaScopes":["default"],"scopedSchemas":[{"id":"A","scopes":["typo"]}],"version":"1"}""")
        }.message!!

        assertTrue(message.contains(SOURCE), message)
        assertTrue(message.contains(ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE), message)
    }

    @Test
    fun `load returns null when no manifest is on the classpath`() {
        assertNull(SchemaScopingManifest.load(URLClassLoader(emptyArray(), null)))
    }

    @Test
    fun `load reads the single manifest on the classpath`(
        @TempDir root: File
    ) {
        val classLoader = classLoaderOver(root, "one" to SchemaScopingManifest.encode(scoping))

        assertEquals(scoping, SchemaScopingManifest.load(classLoader))
    }

    @Test
    fun `load rejects two manifests as ambiguous`(
        @TempDir root: File
    ) {
        val encoded = SchemaScopingManifest.encode(scoping)
        val classLoader = classLoaderOver(root, "one" to encoded, "two" to encoded)

        val message = assertThrows<IllegalStateException> { SchemaScopingManifest.load(classLoader) }.message!!

        assertTrue(message.contains("Found 2"), message)
        assertTrue(message.contains(SchemaScopingManifest.RESOURCE_PATH), message)
    }

    private fun parse(json: String): SchemaScoping = SchemaScopingManifest.parse(json.byteInputStream(), SOURCE)

    private fun classLoaderOver(
        root: File,
        vararg entries: Pair<String, String>,
    ): URLClassLoader {
        val urls = entries.map { (name, content) ->
            val classpathRoot = root.resolve(name)
            classpathRoot.resolve(SchemaScopingManifest.RESOURCE_PATH).apply {
                parentFile.mkdirs()
                writeText(content)
            }
            classpathRoot.toURI().toURL()
        }
        return URLClassLoader(urls.toTypedArray(), null)
    }

    private companion object {
        const val SOURCE = "jar:file:/app.jar!/META-INF/viaduct/schema-scoping.json"
    }
}
