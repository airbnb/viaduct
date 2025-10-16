@file:Suppress("DEPRECATION")

package viaduct.service.runtime

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.engine.EngineFactory
import viaduct.engine.SchemaFactory
import viaduct.engine.api.Engine
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.SchemaId

class EngineRegistryTest {
    companion object {
        private const val SIMPLE_SDL = """
            type Query {
                hello: String
            }
        """

        fun createSchemaFromSdl(sdl: String = SIMPLE_SDL): ViaductSchema {
            val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                SchemaParser().parse(sdl)
            )
            return ViaductSchema(schema = graphQLSchema)
        }

        fun createSchemaFactory(): SchemaFactory {
            val schemaFactory = mockk<SchemaFactory>()
            every {
                schemaFactory.fromSdl(any())
            } answers {
                createSchemaFromSdl(firstArg())
            }
            every {
                schemaFactory.fromResources(any(), any())
            } answers {
                createSchemaFromSdl()
            }
            return schemaFactory
        }

        fun createDocumentProviderFactory() = mockk<DocumentProviderFactory>(relaxed = true)

        fun assertValidSchema(schema: ViaductSchema) {
            assertNotNull(schema.schema, "GraphQL schema should not be null")
            assertNotNull(schema.schema.queryType, "Query type should exist in schema")
            assertEquals("Query", schema.schema.queryType.name, "Query type should be named 'Query'")
            assertNotNull(schema.schema.getType("Query"), "Query type should be retrievable")
        }

        fun createEngineFactory(): EngineFactory {
            return mockk<EngineFactory> {
                every { create(any(), any(), any()) } answers {
                    createEngine(firstArg())
                }
            }
        }

        private fun createEngine(schema: ViaductSchema): Engine {
            return mockk<Engine> {
                every { this@mockk.schema } returns schema
            }
        }
    }

    @Test
    fun `Factory create - successful creation with full schema only`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)
    }

    @Test
    fun `Factory create - successful creation with full and scoped schemas`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "admin", scopeIds = setOf("admin")),
            SchemaConfiguration.ScopeConfig(id = "public", scopeIds = setOf("public"))
        )
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)

        val adminSchema = registry.getSchema(SchemaId.Scoped("admin", setOf("admin")))
        assertValidSchema(adminSchema)

        val publicSchema = registry.getSchema(SchemaId.Scoped("public", setOf("public")))
        assertValidSchema(publicSchema)
    }

    @Test
    fun `Factory create - handles lazy schemas correctly`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "lazy-scope", scopeIds = setOf("lazy"))
        )
        val config = SchemaConfiguration.fromSdl(
            SIMPLE_SDL,
            scopes = scopeConfigs,
            lazyScopedSchemas = true
        )

        val registry = factory.create(config)

        val lazySchema = registry.getSchema(SchemaId.Scoped("lazy-scope", setOf("lazy")))
        assertValidSchema(lazySchema)
    }

    @Test
    fun `getSchema - throws SchemaNotFoundException for invalid schema ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)

        val invalidId = SchemaId.Scoped("nonexistent", setOf("test"))

        val exception = assertThrows(EngineRegistry.SchemaNotFoundException::class.java) {
            registry.getSchema(invalidId)
        }

        assertEquals(
            "No schema registered for schema ID: Scoped(id=nonexistent, scopeIds=[test])",
            exception.message
        )
    }

    @Test
    fun `getSchema - multiple accesses return same schema instance`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "lazy-test", scopeIds = setOf("lazy"))
        )
        val config = SchemaConfiguration.fromSdl(
            SIMPLE_SDL,
            scopes = scopeConfigs,
            lazyScopedSchemas = true
        )
        val registry = factory.create(config)

        val lazySchemaId = SchemaId.Scoped("lazy-test", setOf("lazy"))

        val schema1 = registry.getSchema(lazySchemaId)
        val schema2 = registry.getSchema(lazySchemaId)
        val schema3 = registry.getSchema(lazySchemaId)

        assertSame(schema1, schema2)
        assertSame(schema2, schema3)
    }

    @Test
    fun `getEngine - returns Engine for valid schema ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val engine = registry.getEngine(SchemaId.Full)

        assertNotNull(engine)
    }

    @Test
    fun `getEngine - caches Engine instances`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val engine1 = registry.getEngine(SchemaId.Full)
        val engine2 = registry.getEngine(SchemaId.Full)
        val engine3 = registry.getEngine(SchemaId.Full)

        assertSame(engine1, engine2)
        assertSame(engine2, engine3)
    }

    @Test
    fun `getEngine - creates separate Engine for each schema ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "admin", scopeIds = setOf("admin"))
        )
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val fullEngine = registry.getEngine(SchemaId.Full)
        val adminEngine = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))

        assertNotNull(fullEngine)
        assertNotNull(adminEngine)
        assertNotSame(fullEngine, adminEngine)
    }

    @Test
    fun `getEngine - throws SchemaNotFoundException for invalid schema ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val invalidId = SchemaId.Scoped("nonexistent", setOf("test"))

        val exception = assertThrows(EngineRegistry.SchemaNotFoundException::class.java) {
            registry.getEngine(invalidId)
        }

        assertEquals(
            "No schema registered for schema ID: Scoped(id=nonexistent, scopeIds=[test])",
            exception.message
        )
    }

    @Test
    fun `getEngine - works with lazy schemas`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "lazy-engine", scopeIds = setOf("lazy"))
        )
        val config = SchemaConfiguration.fromSdl(
            SIMPLE_SDL,
            scopes = scopeConfigs,
            lazyScopedSchemas = true
        )
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val lazySchemaId = SchemaId.Scoped("lazy-engine", setOf("lazy"))

        val engine = registry.getEngine(lazySchemaId)

        assertNotNull(engine)
    }

    @Test
    fun `Factory create - handles fromResources configuration`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "resources-scope", scopeIds = setOf("resource"))
        )
        val config = SchemaConfiguration.fromResources(
            packagePrefix = "com.test.schema",
            resourcesIncluded = Regex(".*\\.graphqls"),
            scopes = scopeConfigs
        )
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)

        val scopedSchema = registry.getSchema(SchemaId.Scoped("resources-scope", setOf("resource")))
        assertValidSchema(scopedSchema)
    }

    @Test
    fun `Factory create - handles fromSchema configuration`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val baseSchema = createSchemaFromSdl()
        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "from-schema", scopeIds = setOf("test"))
        )
        val config = SchemaConfiguration.fromSchema(
            schema = baseSchema,
            scopes = scopeConfigs
        )
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)
        assertSame(baseSchema.schema, fullSchema.schema, "fromSchema should use the exact provided schema")

        val scopedSchema = registry.getSchema(SchemaId.Scoped("from-schema", setOf("test")))
        assertValidSchema(scopedSchema)
    }

    @Test
    fun `Factory create - builds full schema exactly once for multiple scoped schemas`() {
        val schemaFactory = mockk<SchemaFactory>()
        var buildCount = 0
        every { schemaFactory.fromSdl(any()) } answers {
            buildCount++
            createSchemaFromSdl(firstArg())
        }

        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig("admin", setOf("admin")),
            SchemaConfiguration.ScopeConfig("public", setOf("public")),
            SchemaConfiguration.ScopeConfig("internal", setOf("internal"))
        )
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL, scopes = scopeConfigs)

        factory.create(config)

        assertEquals(1, buildCount, "SchemaFactory.fromSdl should be called exactly once, not once per scope")
    }

    @Test
    fun `getEngine - caches engine instances per schema ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "admin", scopeIds = setOf("admin"))
        )
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val fullEngine1 = registry.getEngine(SchemaId.Full)
        val fullEngine2 = registry.getEngine(SchemaId.Full)
        val fullEngine3 = registry.getEngine(SchemaId.Full)

        val adminEngine1 = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))
        val adminEngine2 = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))

        assertSame(fullEngine1, fullEngine2, "Repeated calls for Full should return same engine")
        assertSame(fullEngine2, fullEngine3, "Repeated calls for Full should return same engine")
        assertSame(adminEngine1, adminEngine2, "Repeated calls for admin should return same engine")
    }

    @Test
    fun `getEngine - creates distinct engines for different schema IDs`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "admin", scopeIds = setOf("admin")),
            SchemaConfiguration.ScopeConfig(id = "public", scopeIds = setOf("public")),
            SchemaConfiguration.ScopeConfig(id = "internal", scopeIds = setOf("internal"))
        )
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val fullEngine = registry.getEngine(SchemaId.Full)
        val adminEngine = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))
        val publicEngine = registry.getEngine(SchemaId.Scoped("public", setOf("public")))
        val internalEngine = registry.getEngine(SchemaId.Scoped("internal", setOf("internal")))

        assertNotSame(fullEngine, adminEngine, "Full and admin engines should be different")
        assertNotSame(fullEngine, publicEngine, "Full and public engines should be different")
        assertNotSame(adminEngine, publicEngine, "Admin and public engines should be different")
        assertNotSame(adminEngine, internalEngine, "Admin and internal engines should be different")
        assertNotSame(publicEngine, internalEngine, "Public and internal engines should be different")
    }

    // Tests for deprecated registerSchema() API
    // TODO: Remove these tests when registerSchema() is deleted

    @Test
    fun `registerSchema - can register schema dynamically with compute block`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val customSchemaId = SchemaId.Scoped("custom", setOf("custom"))

        config.registerSchema(customSchemaId, { createSchemaFromSdl() })

        val registry = factory.create(config)

        val customSchema = registry.getSchema(customSchemaId)
        assertValidSchema(customSchema)
    }

    @Test
    fun `registerSchema - lazy schema is initialized on first access`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val lazySchemaId = SchemaId.Scoped("lazy-registered", setOf("lazy"))

        var computeBlockCalled = false
        config.registerSchema(
            lazySchemaId,
            {
                computeBlockCalled = true
                createSchemaFromSdl()
            },
            lazy = true
        )

        val registry = factory.create(config)

        assertEquals(false, computeBlockCalled)

        registry.getSchema(lazySchemaId)

        assertEquals(true, computeBlockCalled)
    }

    @Test
    fun `registerSchema - non-lazy schema is initialized immediately during create`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val eagerSchemaId = SchemaId.Scoped("eager-registered", setOf("eager"))

        var computeBlockCalled = false
        config.registerSchema(
            eagerSchemaId,
            {
                computeBlockCalled = true
                createSchemaFromSdl()
            }
        )

        assertEquals(false, computeBlockCalled)

        factory.create(config)

        assertEquals(true, computeBlockCalled)
    }

    @Test
    fun `registerSchema - does not replace existing registration with same ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val schemaId = SchemaId.Scoped("duplicate-test", setOf("test"))

        val firstSchema = createSchemaFromSdl("type Query { first: String }")
        val secondSchema = createSchemaFromSdl("type Query { second: String }")

        config.registerSchema(schemaId, { firstSchema })
        config.registerSchema(schemaId, { secondSchema })

        val registry = factory.create(config)
        val retrievedSchema = registry.getSchema(schemaId)

        assertSame(firstSchema.schema, retrievedSchema.schema)
        assertNotSame(secondSchema.schema, retrievedSchema.schema)
    }

    @Test
    fun `registerSchema - can work alongside fromSdl schemas`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfig = SchemaConfiguration.ScopeConfig(id = "fromSdl", scopeIds = setOf("sdl"))
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL, scopes = setOf(scopeConfig))

        val registeredSchemaId = SchemaId.Scoped("registered", setOf("registered"))
        config.registerSchema(registeredSchemaId, { createSchemaFromSdl() })

        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)

        val fromSdlSchema = registry.getSchema(SchemaId.Scoped("fromSdl", setOf("sdl")))
        assertValidSchema(fromSdlSchema)

        val registeredSchema = registry.getSchema(registeredSchemaId)
        assertValidSchema(registeredSchema)
    }

    @Test
    fun `registerSchema - registered schemas work with getEngine`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registeredSchemaId = SchemaId.Scoped("engine-test", setOf("engine"))

        config.registerSchema(registeredSchemaId, { createSchemaFromSdl() })

        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val engine = registry.getEngine(registeredSchemaId)

        assertNotNull(engine)

        val engine2 = registry.getEngine(registeredSchemaId)
        assertSame(engine, engine2)
    }
}
