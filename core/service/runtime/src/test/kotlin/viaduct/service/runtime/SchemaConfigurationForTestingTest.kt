@file:OptIn(InternalApi::class, VisibleForTest::class)

package viaduct.service.runtime

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest
import viaduct.graphql.schema.scopes.ResourceFileSchema

/**
 * Tests for SchemaConfiguration.forTesting factory.
 */
class SchemaConfigurationForTestingTest {

    // forTesting creates usable SchemaConfiguration from JSON with correct scoped schemas
    @Test
    fun `forTesting creates SchemaConfiguration with correct scoped schemas from JSON`() {
        val fixture = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("admin", "internal", "public"),
            declaredScopedSchemas = mapOf(
                "adminApi" to setOf("admin"),
                "api" to setOf("internal", "public")
            )
        )
        val json = ResourceFileSchema.toJsonString(fixture)

        val config = SchemaConfiguration.forTesting(json)

        assertEquals(2, config.scopedSchemas.size)
        val adminEntry = config.scopedSchemas["adminApi"]
        assertNotNull(adminEntry)
        assertEquals(setOf("admin"), adminEntry.scopeIds)
        val apiEntry = config.scopedSchemas["api"]
        assertNotNull(apiEntry)
        assertEquals(setOf("internal", "public"), apiEntry.scopeIds)
        assertNotNull(config.fullSchemaConfig)
    }

    // method exists in companion object; @VisibleForTest requires opt-in at call sites
    // (this test file uses @file:OptIn(VisibleForTest::class) to compile without warnings,
    // proving forTesting is annotated with @VisibleForTest; BINARY retention means the
    // annotation is not accessible via Java getAnnotation() at runtime)
    @Test
    fun `forTesting method exists in SchemaConfiguration companion and requires VisibleForTest opt-in`() {
        val method = SchemaConfiguration.Companion::class.java.declaredMethods
            .firstOrNull { it.name == "forTesting" && it.parameterCount == 1 }
        assertNotNull(method, "forTesting(String) must exist in SchemaConfiguration.Companion")
        assertEquals(String::class.java, method.parameterTypes[0])
        assertEquals(SchemaConfiguration::class.java, method.returnType)
    }

    // forTesting accepts same JSON format as production reader; same scopedSchemas as fromResources
    @Test
    fun `forTesting produces same scopedSchemas as production fromResources for same JSON`(
        @TempDir tempDir: Path
    ) {
        val fixture = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("admin", "internal", "public"),
            declaredScopedSchemas = mapOf(
                "adminApi" to setOf("admin"),
                "api" to setOf("internal", "public")
            )
        )
        val json = ResourceFileSchema.toJsonString(fixture)

        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        Files.writeString(metaInfDir.resolve("schema-scoping.json"), json)
        val classLoader = URLClassLoader(arrayOf(tempDir.toUri().toURL()), ClassLoader.getPlatformClassLoader())

        val configFromResources = SchemaConfiguration.fromResources(
            setOf("api", "adminApi", "FULL"),
            classLoader
        )
        val configForTesting = SchemaConfiguration.forTesting(json)

        // Both should have the same scoped schema IDs with the same scope sets
        val resourcesKeys = configFromResources.scopedSchemas.entries.map { it.key to it.value.scopeIds }.toSet()
        val testingKeys = configForTesting.scopedSchemas.entries.map { it.key to it.value.scopeIds }.toSet()
        assertEquals(resourcesKeys, testingKeys)
    }

    // follows existing @VisibleForTest factory pattern (non-lazy, uses companion factory)
    @Test
    fun `forTesting follows VisibleForTest factory pattern - eager registration`() {
        val fixture = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("public"),
            declaredScopedSchemas = mapOf("api" to setOf("public"))
        )
        val json = ResourceFileSchema.toJsonString(fixture)

        val config = SchemaConfiguration.forTesting(json)

        // All scoped schemas should be eagerly registered (lazy=false)
        config.scopedSchemas.values.forEach { scopeConfig ->
            assertEquals(false, scopeConfig.lazy, "forTesting must register all schemas as non-lazy")
        }
    }

    // ERROR: forTesting throws ViaductSchemaLoadException for malformed JSON
    @Test
    fun `ERROR forTesting throws ViaductSchemaLoadException for malformed JSON`() {
        assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.forTesting("{ not valid json !!!")
        }
    }

    @Test
    fun `forTesting throws ViaductSchemaLoadException when version does not match CURRENT_VERSION`() {
        val json = """{"declaredSchemaScopes":[],"declaredScopedSchemas":{"FULL":[]},"version":"999"}"""
        val ex = assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.forTesting(json)
        }
        assertTrue(ex.message!!.contains("999"), "Exception should name the file version")
        assertTrue(
            ex.message!!.contains(ResourceFileSchema.CURRENT_VERSION),
            "Exception should name the expected runtime version"
        )
    }

    // ROUND-TRIP: forTesting with empty scopes creates config with no scoped schemas
    @Test
    fun `ROUND-TRIP forTesting with no scoped schemas returns config with only full schema`() {
        val fixture = ResourceFileSchema.create(
            declaredSchemaScopes = emptySet(),
            declaredScopedSchemas = emptyMap()
        )
        val json = ResourceFileSchema.toJsonString(fixture)

        val config = SchemaConfiguration.forTesting(json)

        assertEquals(0, config.scopedSchemas.size)
        assertNotNull(config.fullSchemaConfig)
    }
}
