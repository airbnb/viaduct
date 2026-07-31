@file:Suppress("DEPRECATION")

package viaduct.service.runtime

import graphql.ExecutionInput
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineFactory
import viaduct.engine.EngineImpl
import viaduct.engine.SchemaFactory
import viaduct.engine.api.Engine
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.withThreadLocalCoroutineContext
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.service.api.SchemaId

class EngineRegistryTest {
    companion object {
        private const val SIMPLE_SDL = """
            type Query {
                hello: String
            }
        """

        private const val SCOPED_SDL = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

            type Query @scope(to: ["admin", "public", "internal", "lazy", "resource", "test", "sdl"]) {
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
                createSchemaFromSdl(SCOPED_SDL)
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
    fun `Factory create - successful creation with base schema only`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(SIMPLE_SDL)
        val registry = factory.create(config)

        val baseSchema = registry.getSchema(SchemaId.Base)
        assertValidSchema(baseSchema)
    }

    @Test
    fun `Factory create - base schema filters tenant-local fields but full schema keeps them`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)
        val sdl = """
            directive @tenantLocal on FIELD_DEFINITION

            type Query {
                publicField: String
                internalOnly: String @tenantLocal
            }
        """.trimIndent()

        val registry = factory.create(SchemaConfiguration.fromSdl(sdl))

        val baseSchema = registry.getSchema(SchemaId.Base)
        val fullSchema = registry.getFullSchema()
        assertNotNull(baseSchema.schema.queryType.getFieldDefinition("publicField"))
        assertNull(baseSchema.schema.queryType.getFieldDefinition("internalOnly"))
        assertNotNull(fullSchema.schema.queryType.getFieldDefinition("internalOnly"))
    }

    @Test
    fun `Factory create - scoped schemas filter tenant-local fields from full schema`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)
        val sdl = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            directive @tenantLocal on FIELD_DEFINITION

            type Query @scope(to: ["public"]) {
                publicField: String
                internalOnly: String @tenantLocal
            }
        """.trimIndent()
        val schemaId = SchemaId.Scoped("public", setOf("public"))

        val registry = factory.create(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(SchemaConfiguration.ScopeConfig.Scoped(schemaId.id, schemaId.scopeIds)),
            )
        )

        val scopedSchema = registry.getSchema(schemaId)
        assertNotNull(scopedSchema.schema.queryType.getFieldDefinition("publicField"))
        assertNull(scopedSchema.schema.queryType.getFieldDefinition("internalOnly"))
        assertNotNull(registry.getFullSchema().schema.queryType.getFieldDefinition("internalOnly"))
    }

    @Test
    fun `Factory create - explicit base schema config uses the base view`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)
        val sdl = """
            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION
            directive @tenantLocal on FIELD_DEFINITION

            type Query @scope(to: ["public"]) {
                publicField: String
                internalOnly: String @tenantLocal
            }
        """.trimIndent()
        val registry = factory.create(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(SchemaConfiguration.ScopeConfig.Base),
            )
        )

        val baseSchema = registry.getSchema(SchemaId.Base)
        assertNotNull(baseSchema.schema.queryType.getFieldDefinition("publicField"))
        assertNull(baseSchema.schema.queryType.getFieldDefinition("internalOnly"))
        assertNotNull(registry.getFullSchema().schema.queryType.getFieldDefinition("internalOnly"))
    }

    @Test
    fun `Base scope configs preserve the source schema scoping mode`() {
        val scopeConfig = SchemaConfiguration.ScopeConfig.Base

        val scopingMode = scopeConfig.scopingMode(createSchemaFromSdl(SCOPED_SDL))

        assertEquals(
            setOf("admin", "public", "internal", "lazy", "resource", "test", "sdl"),
            (scopingMode as SchemaScopingMode.ScopeAware).validScopes,
        )
    }

    @Test
    fun `Scoped scope configs use the source schema valid scopes`() {
        val scopeConfig = SchemaConfiguration.ScopeConfig.Scoped("public", setOf("public"))

        val scopingMode = scopeConfig.scopingMode(createSchemaFromSdl(SCOPED_SDL))

        assertEquals(
            setOf("admin", "public", "internal", "lazy", "resource", "test", "sdl"),
            (scopingMode as SchemaScopingMode.ScopeAware).validScopes,
        )
    }

    @Test
    fun `Factory create - successful creation with base and scoped schemas`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig.Scoped(id = "admin", scopeIds = setOf("admin")),
            SchemaConfiguration.ScopeConfig.Scoped(id = "public", scopeIds = setOf("public"))
        )
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)

        val baseSchema = registry.getSchema(SchemaId.Base)
        assertValidSchema(baseSchema)

        val adminSchema = registry.getSchema(SchemaId.Scoped("admin", setOf("admin")))
        assertValidSchema(adminSchema)

        val publicSchema = registry.getSchema(SchemaId.Scoped("public", setOf("public")))
        assertValidSchema(publicSchema)
    }

    @Test
    fun `Factory create - scoped views require a scope-aware schema`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val config = SchemaConfiguration.fromSdl(
            SIMPLE_SDL,
            scopes = setOf(SchemaConfiguration.ScopeConfig.Scoped(id = "admin", scopeIds = setOf("admin")))
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            factory.create(config)
        }

        assertTrue(
            exception.message!!.contains(
                "Cannot build a scoped schema view from an unscoped schema."
            ),
            exception.message,
        )
    }

    @Test
    fun `Factory create - handles lazy schemas correctly`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig.Scoped(id = "lazy-scope", scopeIds = setOf("lazy"))
        )
        val config = SchemaConfiguration.fromSdl(
            SCOPED_SDL,
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
            SchemaConfiguration.ScopeConfig.Scoped(id = "lazy-test", scopeIds = setOf("lazy"))
        )
        val config = SchemaConfiguration.fromSdl(
            SCOPED_SDL,
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

        val engine = registry.getEngine(SchemaId.Base)

        assertNotNull(engine)
    }

    @Test
    fun `getEngine - validates against base schema but passes full schema to engine factory`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)
        val sdl = """
            directive @tenantLocal on FIELD_DEFINITION

            type Query {
                publicField: String
                internalOnly: String @tenantLocal
            }
        """.trimIndent()
        var selectedSchema: ViaductSchema? = null
        var fullSchema: ViaductSchema? = null
        val engineFactory = mockk<EngineFactory> {
            every { create(any(), any(), any()) } answers {
                selectedSchema = firstArg()
                fullSchema = thirdArg()
                createEngine(firstArg())
            }
        }
        val registry = factory.create(SchemaConfiguration.fromSdl(sdl))
        registry.setEngineFactory(engineFactory)

        registry.getEngine(SchemaId.Base)

        assertNull(selectedSchema!!.schema.queryType.getFieldDefinition("internalOnly"))
        assertNotNull(fullSchema!!.schema.queryType.getFieldDefinition("internalOnly"))
    }

    @Test
    fun `getFullSchemaGraphQLEngine_DONOTUSE - validates against full schema`() {
        val factory = EngineRegistry.Factory(
            createSchemaFactory(),
            DocumentProviderFactory { _, _ -> NoOpPreparsedDocumentProvider() }
        )
        val sdl = """
            directive @tenantLocal on FIELD_DEFINITION

            type Query {
                publicField: String
                internalOnly: String @tenantLocal
            }
        """.trimIndent()
        val registry = factory.create(SchemaConfiguration.fromSdl(sdl))
        registry.setEngineFactory(EngineFactory())

        val baseEngine = registry.getEngine(SchemaId.Base) as EngineImpl
        val fullSchemaEngine = registry.getFullSchemaEngine() as EngineImpl
        val baseResult =
            executeQuery(
                registry.getGraphQLEngine_DONOTUSE(SchemaId.Base),
                executionInputFor("{ internalOnly }", baseEngine)
            )
        val fullResult =
            executeQuery(
                registry.getFullSchemaGraphQLEngine_DONOTUSE(),
                executionInputFor("{ internalOnly }", fullSchemaEngine)
            )

        assertTrue(
            baseResult.errors.any { it.message.contains("Field 'internalOnly' in type 'Query' is undefined") },
            baseResult.errors.toString()
        )
        assertTrue(fullResult.errors.isEmpty(), fullResult.errors.toString())
    }

    @Test
    fun `getFullSchemaEngine - uses a distinct document provider schema ID`() {
        val providersBySchemaId = mutableMapOf<SchemaId, NoOpPreparsedDocumentProvider>()
        val documentProviderFactory = DocumentProviderFactory { schemaId, _ ->
            providersBySchemaId.getOrPut(schemaId) { NoOpPreparsedDocumentProvider() }
        }
        val registry = EngineRegistry.Factory(
            createSchemaFactory(),
            documentProviderFactory,
        ).create(SchemaConfiguration.fromSdl(SIMPLE_SDL))
        registry.setEngineFactory(createEngineFactory())

        registry.getEngine(SchemaId.Base)
        registry.getFullSchemaEngine()

        assertEquals(2, providersBySchemaId.size)
        val baseProvider = providersBySchemaId.getValue(SchemaId.Base)
        val fullSchemaProvider = providersBySchemaId.entries.single { it.key != SchemaId.Base }.value
        assertNotSame(baseProvider, fullSchemaProvider)
    }

    private fun executionInputFor(
        query: String,
        engine: EngineImpl
    ): ExecutionInput =
        ExecutionInput.newExecutionInput()
            .query(query)
            .localContext(CompositeLocalContext.withContexts(engine.createEngineExecutionContext(null)))
            .build()

    private fun executeQuery(
        engine: graphql.GraphQL,
        executionInput: ExecutionInput
    ) = kotlinx.coroutines.runBlocking {
        withThreadLocalCoroutineContext {
            engine.executeAsync(executionInput).await()
        }
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

        val engine1 = registry.getEngine(SchemaId.Base)
        val engine2 = registry.getEngine(SchemaId.Base)
        val engine3 = registry.getEngine(SchemaId.Base)

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
            SchemaConfiguration.ScopeConfig.Scoped(id = "admin", scopeIds = setOf("admin"))
        )
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val baseEngine = registry.getEngine(SchemaId.Base)
        val adminEngine = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))

        assertNotNull(baseEngine)
        assertNotNull(adminEngine)
        assertNotSame(baseEngine, adminEngine)
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
            SchemaConfiguration.ScopeConfig.Scoped(id = "lazy-engine", scopeIds = setOf("lazy"))
        )
        val config = SchemaConfiguration.fromSdl(
            SCOPED_SDL,
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
            SchemaConfiguration.ScopeConfig.Scoped(id = "resources-scope", scopeIds = setOf("resource"))
        )
        val config = SchemaConfiguration.fromResources(
            grtPackagePrefix = "com.test.schema",
            resourcesIncluded = Regex(".*\\.graphqls"),
            scopes = scopeConfigs
        )
        val registry = factory.create(config)

        val baseSchema = registry.getSchema(SchemaId.Base)
        assertValidSchema(baseSchema)

        val scopedSchema = registry.getSchema(SchemaId.Scoped("resources-scope", setOf("resource")))
        assertValidSchema(scopedSchema)
    }

    @Test
    fun `Factory create - handles fromSchema configuration`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val baseSchema = createSchemaFromSdl(SCOPED_SDL)
        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig.Scoped(id = "from-schema", scopeIds = setOf("test"))
        )
        val config = SchemaConfiguration.fromSchema(
            schema = baseSchema,
            scopes = scopeConfigs
        )
        val registry = factory.create(config)

        val registeredBaseSchema = registry.getSchema(SchemaId.Base)
        assertValidSchema(registeredBaseSchema)
        assertSame(baseSchema.schema, registeredBaseSchema.schema, "fromSchema should use the exact provided schema")

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
            SchemaConfiguration.ScopeConfig.Scoped("admin", setOf("admin")),
            SchemaConfiguration.ScopeConfig.Scoped("public", setOf("public")),
            SchemaConfiguration.ScopeConfig.Scoped("internal", setOf("internal"))
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
            SchemaConfiguration.ScopeConfig.Scoped(id = "admin", scopeIds = setOf("admin"))
        )
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val baseEngine1 = registry.getEngine(SchemaId.Base)
        val baseEngine2 = registry.getEngine(SchemaId.Base)
        val baseEngine3 = registry.getEngine(SchemaId.Base)

        val adminEngine1 = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))
        val adminEngine2 = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))

        assertSame(baseEngine1, baseEngine2, "Repeated calls for Base should return same engine")
        assertSame(baseEngine2, baseEngine3, "Repeated calls for Base should return same engine")
        assertSame(adminEngine1, adminEngine2, "Repeated calls for admin should return same engine")
    }

    @Test
    fun `getEngine - creates distinct engines for different schema IDs`() {
        val schemaFactory = createSchemaFactory()
        val documentProviderFactory = createDocumentProviderFactory()
        val engineFactory = createEngineFactory()
        val factory = EngineRegistry.Factory(schemaFactory, documentProviderFactory)

        val scopeConfigs = setOf(
            SchemaConfiguration.ScopeConfig.Scoped(id = "admin", scopeIds = setOf("admin")),
            SchemaConfiguration.ScopeConfig.Scoped(id = "public", scopeIds = setOf("public")),
            SchemaConfiguration.ScopeConfig.Scoped(id = "internal", scopeIds = setOf("internal"))
        )
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = scopeConfigs)
        val registry = factory.create(config)
        registry.setEngineFactory(engineFactory)

        val baseEngine = registry.getEngine(SchemaId.Base)
        val adminEngine = registry.getEngine(SchemaId.Scoped("admin", setOf("admin")))
        val publicEngine = registry.getEngine(SchemaId.Scoped("public", setOf("public")))
        val internalEngine = registry.getEngine(SchemaId.Scoped("internal", setOf("internal")))

        assertNotSame(baseEngine, adminEngine, "Base and admin engines should be different")
        assertNotSame(baseEngine, publicEngine, "Base and public engines should be different")
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

        val scopeConfig = SchemaConfiguration.ScopeConfig.Scoped(id = "fromSdl", scopeIds = setOf("sdl"))
        val config = SchemaConfiguration.fromSdl(SCOPED_SDL, scopes = setOf(scopeConfig))

        val registeredSchemaId = SchemaId.Scoped("registered", setOf("registered"))
        config.registerSchema(registeredSchemaId, { createSchemaFromSdl() })

        val registry = factory.create(config)

        val baseSchema = registry.getSchema(SchemaId.Base)
        assertValidSchema(baseSchema)

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
