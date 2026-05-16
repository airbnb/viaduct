package viaduct.service.runtime

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.scopes.ResourceFileSchema

/**
 * Tests for SchemaConfiguration.fromResources(schemaIds) and resolveSchemaId.
 * The test fixture at META-INF/viaduct/schema-scoping.json declares:
 *   declaredSchemaScopes: [admin, internal, public]
 *   declaredScopedSchemas: { FULL: [], adminApi: [admin], api: [internal, public] }
 *   version: 1
 */
class SchemaConfigurationFromResourcesTest {

    // fromResources resolves schema IDs correctly from a META-INF fixture
    @Test
    fun `fromResources resolves schema IDs from fixture`() {
        val config = SchemaConfiguration.fromResources(setOf("api", "adminApi", "FULL"))

        assertEquals(2, config.scopedSchemas.size)

        val apiEntry = config.scopedSchemas["api"]
        assertNotNull(apiEntry)
        assertEquals(setOf("internal", "public"), apiEntry.scopeIds)

        val adminEntry = config.scopedSchemas["adminApi"]
        assertNotNull(adminEntry)
        assertEquals(setOf("admin"), adminEntry.scopeIds)
    }

    // resolveSchemaId returns correct scope set for declared id; returns emptySet for FULL
    @Test
    fun `resolveSchemaId returns scope set and emptySet for FULL`() {
        val config = SchemaConfiguration.fromResources(setOf("api", "adminApi"))

        assertEquals(setOf("internal", "public"), config.resolveSchemaId("api"))
        assertEquals(setOf("admin"), config.resolveSchemaId("adminApi"))
        assertEquals(emptySet<String>(), config.resolveSchemaId("FULL"))
    }

    // FULL schema ID is always resolvable
    @Test
    fun `FULL schema ID is always resolvable`() {
        val config = SchemaConfiguration.fromResources(setOf("api"))
        assertEquals(emptySet<String>(), config.resolveSchemaId("FULL"))
    }

    // empty scope set resolves to full schema (via the existing applyScopes path)
    @Test
    fun `schema with empty scopeIds resolves to full schema via applyScopes`(
        @TempDir tempDir: Path
    ) {
        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        val fixture = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("public"),
            declaredScopedSchemas = mapOf("api" to setOf("public"), "emptyScoped" to emptySet())
        )
        Files.writeString(
            metaInfDir.resolve("schema-scoping.json"),
            ResourceFileSchema.toJsonString(fixture)
        )
        val classLoader = URLClassLoader(arrayOf(tempDir.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        val config = SchemaConfiguration.fromResources(setOf("emptyScoped"), classLoader)

        val emptyEntry = config.scopedSchemas["emptyScoped"]
        assertNotNull(emptyEntry)
        assertTrue(emptyEntry.scopeIds.isEmpty(), "Expected empty scopeIds for emptyScoped schema")
        // The ScopedSchemaConfig.Derived.build path returns the full schema when scopeIds is empty
        assertEquals(emptySet<String>(), config.resolveSchemaId("emptyScoped"))
    }

    // resource file read once, cached immutably
    @Test
    fun `resource file is loaded at construction time not on each resolveSchemaId`() {
        var openCount = 0
        val parent = Thread.currentThread().contextClassLoader
            ?: SchemaConfigurationFromResourcesTest::class.java.classLoader
        val countingLoader = object : ClassLoader(parent) {
            override fun getResourceAsStream(name: String): java.io.InputStream? {
                if (name == "META-INF/viaduct/schema-scoping.json") {
                    openCount++
                }
                return super.getResourceAsStream(name)
            }
        }

        val config = SchemaConfiguration.fromResources(setOf("api", "adminApi"), countingLoader)

        assertEquals(1, openCount, "Resource file must be opened exactly once during construction")

        val expected = setOf("internal", "public")
        repeat(50) {
            assertEquals(expected, config.resolveSchemaId("api"))
        }

        assertEquals(1, openCount, "resolveSchemaId must not re-open the resource file")
    }

    // SchemaConfiguration immutable after construction; concurrent reads safe
    @Test
    fun `concurrent reads are safe`() {
        val config = SchemaConfiguration.fromResources(setOf("api", "adminApi"))
        val latch = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Set<String>>()

        val threads = (1..30).map {
            Thread {
                latch.await()
                results.add(config.resolveSchemaId("api"))
            }.apply { start() }
        }
        latch.countDown()
        threads.forEach { it.join() }

        assertEquals(30, results.size)
        results.forEach { assertEquals(setOf("internal", "public"), it) }
    }

    // ERROR: fromResources throws ViaductInvalidConfigurationException for undeclared schema id
    @Test
    fun `ERROR fromResources throws ViaductInvalidConfigurationException for undeclared schema id`() {
        val ex = assertThrows<ViaductInvalidConfigurationException> {
            SchemaConfiguration.fromResources(setOf("undeclaredId"))
        }
        assertTrue(ex.message!!.contains("undeclaredId"), "Exception message should contain the offending id")
        assertTrue(
            ex.message!!.contains("api") || ex.message!!.contains("adminApi"),
            "Exception message should contain declared ids"
        )
    }

    // ERROR: fromResources throws ViaductSchemaLoadException for missing META-INF file (non-FULL ids)
    @Test
    fun `ERROR fromResources throws ViaductSchemaLoadException for missing META-INF file`() {
        val classLoader = URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
        assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.fromResources(setOf("api"), classLoader)
        }
    }

    // ERROR: fromResources throws ViaductSchemaLoadException for malformed JSON
    @Test
    fun `ERROR fromResources throws ViaductSchemaLoadException for malformed JSON`(
        @TempDir tempDir: Path
    ) {
        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        Files.writeString(metaInfDir.resolve("schema-scoping.json"), "{ not valid json !!!")

        val classLoader = URLClassLoader(arrayOf(tempDir.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.fromResources(setOf("api"), classLoader)
        }
    }

    // ROUND-TRIP: build fixture → serialize → fromResources recovers exact scope sets
    @Test
    fun `ROUND-TRIP fromResources recovers exact scope sets from serialized ResourceFileSchema`(
        @TempDir tempDir: Path
    ) {
        val original = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("public", "internal", "admin"),
            declaredScopedSchemas = mapOf(
                "api" to setOf("public", "internal"),
                "adminApi" to setOf("admin")
            )
        )

        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        val json = ResourceFileSchema.toJsonString(original)
        Files.writeString(metaInfDir.resolve("schema-scoping.json"), json)

        val classLoader = URLClassLoader(arrayOf(tempDir.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        val config = SchemaConfiguration.fromResources(setOf("api", "adminApi", "FULL"), classLoader)

        assertEquals(original.declaredScopedSchemas["api"], config.resolveSchemaId("api"))
        assertEquals(original.declaredScopedSchemas["adminApi"], config.resolveSchemaId("adminApi"))
        assertEquals(emptySet<String>(), config.resolveSchemaId("FULL"))
    }

    // NoScopesMode: fromResources(setOf("FULL")) succeeds without META-INF file
    @Test
    fun `NoScopesMode fromResources with only FULL succeeds when no META-INF file present`() {
        val classLoader = URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
        val config = SchemaConfiguration.fromResources(setOf("FULL"), classLoader)

        assertEquals(0, config.scopedSchemas.size)
        assertNotNull(config.fullSchemaConfig)
    }

    // resolveSchemaId throws for undeclared id on instance level
    @Test
    fun `resolveSchemaId throws ViaductInvalidConfigurationException for unregistered id`() {
        val config = SchemaConfiguration.fromResources(setOf("api"))

        val ex = assertThrows<ViaductInvalidConfigurationException> {
            config.resolveSchemaId("notRegistered")
        }
        assertTrue(ex.message!!.contains("notRegistered"))
    }

    @Test
    fun `fromResources throws ViaductSchemaLoadException when version field does not match CURRENT_VERSION`(
        @TempDir tempDir: Path
    ) {
        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        Files.writeString(
            metaInfDir.resolve("schema-scoping.json"),
            """{"declaredSchemaScopes":[],"declaredScopedSchemas":{"FULL":[]},"version":"999"}"""
        )
        val classLoader = URLClassLoader(
            arrayOf(tempDir.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        )
        val ex = assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.fromResources(setOf("FULL"), classLoader)
        }
        assertTrue(ex.message!!.contains("999"), "Exception should name the file version")
        assertTrue(
            ex.message!!.contains(ResourceFileSchema.CURRENT_VERSION),
            "Exception should name the expected runtime version"
        )
    }

    @Test
    fun `version mismatch error message names file version and runtime version verbatim`(
        @TempDir tempDir: Path
    ) {
        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        Files.writeString(
            metaInfDir.resolve("schema-scoping.json"),
            """{"declaredSchemaScopes":[],"declaredScopedSchemas":{"FULL":[]},"version":"42"}"""
        )
        val classLoader = URLClassLoader(
            arrayOf(tempDir.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        )
        val ex = assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.fromResources(setOf("FULL"), classLoader)
        }
        assertTrue(ex.message!!.contains("'42'"), "Message must include literal file version '42'")
        assertTrue(
            ex.message!!.contains("'${ResourceFileSchema.CURRENT_VERSION}'"),
            "Message must include literal runtime version"
        )
    }

    // A resource file with no `version` field at all should fail loudly rather than load with
    // an implicit default. This guards against silent acceptance of pre-versioning artifacts.
    @Test
    fun `fromResources throws ViaductSchemaLoadException when version field is absent`(
        @TempDir tempDir: Path
    ) {
        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        Files.writeString(
            metaInfDir.resolve("schema-scoping.json"),
            """{"declaredSchemaScopes":[],"declaredScopedSchemas":{"FULL":[]}}"""
        )
        val classLoader = URLClassLoader(
            arrayOf(tempDir.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        )
        assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.fromResources(setOf("FULL"), classLoader)
        }
    }

    // An empty `version` string is treated as a mismatch (not as the default), and the error
    // message must still name both versions verbatim.
    @Test
    fun `fromResources throws ViaductSchemaLoadException when version field is empty string`(
        @TempDir tempDir: Path
    ) {
        val metaInfDir = tempDir.resolve("META-INF/viaduct")
        Files.createDirectories(metaInfDir)
        Files.writeString(
            metaInfDir.resolve("schema-scoping.json"),
            """{"declaredSchemaScopes":[],"declaredScopedSchemas":{"FULL":[]},"version":""}"""
        )
        val classLoader = URLClassLoader(
            arrayOf(tempDir.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        )
        val ex = assertThrows<ViaductSchemaLoadException> {
            SchemaConfiguration.fromResources(setOf("FULL"), classLoader)
        }
        assertTrue(
            ex.message!!.contains("''"),
            "Message must include the empty literal file version, got: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains("'${ResourceFileSchema.CURRENT_VERSION}'"),
            "Message must include the expected runtime version, got: ${ex.message}"
        )
    }
}
