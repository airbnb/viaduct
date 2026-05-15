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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineFactory
import viaduct.engine.SchemaFactory
import viaduct.engine.api.Engine
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.SchemaId

@Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // intentional use of legacy bootstrap shim
class EngineRegistryTest {
    companion object {
        private const val SIMPLE_SDL = """
            type Query {
                hello: String
            }
        """

        private const val SCOPED_SDL = """
            schema { query: Query }

            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION | SCALAR

            type Query @scope(to: ["*"]) {
                hello: String
            }

            type AdminType @scope(to: ["admin"]) { id: ID! }
            type PublicType @scope(to: ["public"]) { id: ID! }
            type InternalType @scope(to: ["internal"]) { id: ID! }
            type LazyType @scope(to: ["lazy"]) { id: ID! }
            type ResourceType @scope(to: ["resource"]) { id: ID! }
            type TestType @scope(to: ["test"]) { id: ID! }
            type SdlType @scope(to: ["sdl"]) { id: ID! }
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
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)

        val adminSchema = registry.getSchema(SchemaId("admin"))
        assertValidSchema(adminSchema)

        val publicSchema = registry.getSchema(SchemaId("public"))
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
            SCOPED_SDL,
            scopes = scopeConfigs,
            lazyScopedSchemas = true
        )

        val registry = factory.create(config)

        val lazySchema = registry.getSchema(SchemaId("lazy-scope"))
        assertValidSchema(lazySchema)
    }

    @Test
    fun `getSchema - throws SchemaNotFoundException for invalid schema ID`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)
        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)

        val invalidId = SchemaId("nonexistent")

        val exception = assertThrows(EngineRegistry.SchemaNotFoundException::class.java) {
            registry.getSchema(invalidId)
        }

        assertEquals(
            "No schema registered for schema ID: SchemaId(id='nonexistent')",
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
            SCOPED_SDL,
            scopes = scopeConfigs,
            lazyScopedSchemas = true
        )
        val registry = factory.create(config)

        val lazySchemaId = SchemaId("lazy-test")

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
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val fullEngine = registry.getEngine(SchemaId.Full)
        val adminEngine = registry.getEngine(SchemaId("admin"))

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

        val invalidId = SchemaId("nonexistent")

        val exception = assertThrows(EngineRegistry.SchemaNotFoundException::class.java) {
            registry.getEngine(invalidId)
        }

        assertEquals(
            "No schema registered for schema ID: SchemaId(id='nonexistent')",
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
            SCOPED_SDL,
            scopes = scopeConfigs,
            lazyScopedSchemas = true
        )
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val lazySchemaId = SchemaId("lazy-engine")

        val engine = registry.getEngine(lazySchemaId)

        assertNotNull(engine)
    }

    @Test
    fun `Factory create - handles fromResources configuration`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        // Use empty scopeIds so applyScopes returns the full schema without filtering (no @scope directives needed).
        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig(id = "resources-scope", scopeIds = emptySet())
        )
        val config = SchemaConfiguration.fromResources(
            grtPackagePrefix = "com.test.schema",
            resourcesIncluded = Regex(".*\\.graphqls"),
            scopes = scopeConfigs
        )
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)

        val scopedSchema = registry.getSchema(SchemaId("resources-scope"))
        assertValidSchema(scopedSchema)
    }

    @Test
    fun `Factory create - handles fromSchema configuration`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val baseSchema = createSchemaFromSdl(SCOPED_SDL)
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

        val scopedSchema = registry.getSchema(SchemaId("from-schema"))
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
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)

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
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val fullEngine1 = registry.getEngine(SchemaId.Full)
        val fullEngine2 = registry.getEngine(SchemaId.Full)
        val fullEngine3 = registry.getEngine(SchemaId.Full)

        val adminEngine1 = registry.getEngine(SchemaId("admin"))
        val adminEngine2 = registry.getEngine(SchemaId("admin"))

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
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val fullEngine = registry.getEngine(SchemaId.Full)
        val adminEngine = registry.getEngine(SchemaId("admin"))
        val publicEngine = registry.getEngine(SchemaId("public"))
        val internalEngine = registry.getEngine(SchemaId("internal"))

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
        val customSchemaId = SchemaId("custom")

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
        val lazySchemaId = SchemaId("lazy-registered")

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
        val eagerSchemaId = SchemaId("eager-registered")

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
        val schemaId = SchemaId("duplicate-test")

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
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = setOf(scopeConfig))

        val registeredSchemaId = SchemaId("registered")
        config.registerSchema(registeredSchemaId, { createSchemaFromSdl() })

        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertValidSchema(fullSchema)

        val fromSdlSchema = registry.getSchema(SchemaId("fromSdl"))
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
        val registeredSchemaId = SchemaId("engine-test")

        config.registerSchema(registeredSchemaId, { createSchemaFromSdl() })

        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val engine = registry.getEngine(registeredSchemaId)

        assertNotNull(engine)

        val engine2 = registry.getEngine(registeredSchemaId)
        assertSame(engine, engine2)
    }

    @Test
    fun `applyScopes throws ViaductInvalidConfigurationException when scope set is non-empty but SDL has no scope directives`() {
        val noScopeSchema = createSchemaFromSdl(SIMPLE_SDL)
        val derived = SchemaConfiguration.ScopedSchemaConfig.Derived(
            schemaId = SchemaId("api"),
            scopeIds = setOf("public"),
            lazy = false
        )
        val ex = assertThrows(ViaductInvalidConfigurationException::class.java) {
            derived.build(noScopeSchema)
        }
        assertTrue(ex.message!!.contains("public"), "Exception should mention the requested scope set")
        assertTrue(
            ex.message!!.contains("Build-time validation"),
            "Exception should reference Build-time validation"
        )
    }
}
