@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.GraphQLError
import graphql.execution.preparsed.PreparsedDocumentProvider
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ResolverType
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.DocumentProviderFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class Engine2StandardViaductTest {
    @Test
    fun `engine2 attributes production resolver failures to their response path`() {
        val sdl =
            """
            extend type Query {
                failure: String @resolver
            }
            """.trimIndent()
        val suppliedModule =
            EngineTestModule(sdl) {
                field("Query" to "failure") {
                    resolver {
                        fn { _, _, _, _, _ -> throw IllegalStateException("resolver failed") }
                    }
                }
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(operationText = "{ attributed: failure }"),
                    SchemaId.Base,
                )
            }

        assertEquals(mapOf("attributed" to null), result.getData())
        assertEquals(listOf("attributed"), result.errors.single().path)
    }

    @Test
    fun `engine2 receives StandardViaduct operation inputs through its preparsed document provider`() {
        val sdl =
            """
            extend type Query {
                echo(value: String!): String @resolver
            }
            """.trimIndent()
        val requestContext = Any()
        val resolverCalls = AtomicInteger()
        val preparsedDocumentCalls = AtomicInteger()
        val suppliedModule =
            EngineTestModule(sdl) {
                field("Query" to "echo") {
                    resolver {
                        fn { arguments, _, _, _, context ->
                            assertSame(requestContext, context.requestContext)
                            resolverCalls.incrementAndGet()
                            arguments.getValue("value")
                        }
                    }
                }
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withDocumentProviderFactory(
                    DocumentProviderFactory { _, _ ->
                        PreparsedDocumentProvider { input, parseAndValidate ->
                            preparsedDocumentCalls.incrementAndGet()
                            CompletableFuture.completedFuture(parseAndValidate.apply(input))
                        }
                    },
                ).withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(
                        operationText =
                            """
                            query Ignored { echo(value: "ignored") }
                            query Selected(${'$'}value: String!) { echo(value: ${'$'}value) }
                            """.trimIndent(),
                        operationName = "Selected",
                        variables = mapOf("value" to "selected"),
                        requestContext = requestContext,
                    ),
                    SchemaId.Base,
                )
            }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("echo" to "selected"), result.getData())
        assertEquals(1, resolverCalls.get())
        assertEquals(1, preparsedDocumentCalls.get())
    }

    @Test
    fun `engine2 scoped execution resolves private full-schema inputs`() {
        val sdl =
            """
            extend type Query @scope(to: ["scoped"]) {
                publicValue: Int @resolver
            }

            extend type Query @scope(to: ["private"]) {
                privateValue: Int @resolver
            }
            """.trimIndent()
        val suppliedModule =
            EngineTestModule(sdl) {
                fieldWithValue("Query" to "privateValue", 3)
                field("Query" to "publicValue") {
                    resolver {
                        objectSelections("privateValue")
                        fn { _, objectValue, _, _, _ -> objectValue.get("privateValue") }
                    }
                }
            }
        val schemaId = SchemaId.Scoped("scoped", setOf("scoped"))
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(
                    SchemaConfiguration.fromSdl(
                        sdl,
                        scopes =
                            setOf(
                                SchemaConfiguration.ScopeConfig.Scoped(
                                    id = "scoped",
                                    scopeIds = setOf("scoped"),
                                ),
                            ),
                    ),
                ).withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(operationText = "{ publicValue }"),
                    schemaId,
                )
            }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("publicValue" to 3), result.getData())
    }

    @Test
    fun `engine2 invokes production node resolver instrumentation once`() {
        val sdl =
            """
            type User implements Node {
                id: ID!
                name: String!
            }
            """.trimIndent()
        val nodeResolverCalls = AtomicInteger()
        val nodeInstrumentationCalls = AtomicInteger()
        val suppliedModule =
            EngineTestModule(sdl) {
                type("User") {
                    nodeUnbatchedExecutor(selective = true) { _, _, _ ->
                        nodeResolverCalls.incrementAndGet()
                        createEngineObjectData(
                            objectType,
                            mapOf("name" to "Ada"),
                        )
                    }
                }
            }
        val instrumentation =
            object : ViaductResolverInstrumentation {
                override fun <T> instrumentResolverExecution(
                    resolver: ResolverFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): ResolverFunction<T> =
                    if (parameters.fieldCoordinate == null) {
                        ResolverFunction {
                            nodeInstrumentationCalls.incrementAndGet()
                            resolver.resolve()
                        }
                    } else {
                        resolver
                    }
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withResolverInstrumentation(instrumentation)
                .withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()
        val globalId = Base64.getEncoder().encodeToString("User:u1".toByteArray())

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(
                        operationText =
                            """
                            query Node(${'$'}id: ID!) {
                                node(id: ${'$'}id) {
                                    __typename
                                    ... on User { name }
                                }
                            }
                            """.trimIndent(),
                        variables = mapOf("id" to globalId),
                    ),
                    SchemaId.Base,
                )
            }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(
            mapOf("node" to mapOf("__typename" to "User", "name" to "Ada")),
            result.getData(),
        )
        assertEquals(1, nodeResolverCalls.get())
        assertEquals(1, nodeInstrumentationCalls.get())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `engine2 rejects subscriptions before invoking resolvers`() {
        val sdl =
            """
            extend type Query {
                noop: String @resolver
            }

            extend type Subscription {
                events: String @resolver
            }
            """.trimIndent()
        val resolverCalls = AtomicInteger()
        val suppliedModule =
            EngineTestModule(sdl) {
                fieldWithValue("Query" to "noop", "noop")
                field("Subscription" to "events") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            resolverCalls.incrementAndGet()
                            "event"
                        }
                    }
                }
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).allowSubscriptions(true)
                .build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(operationText = "subscription { events }"),
                    SchemaId.Base,
                )
            }

        assertEquals(listOf("Engine2 does not support subscriptions"), result.errors.map { it.message })
        assertEquals(0, resolverCalls.get())
    }

    @Test
    fun `engine2 nested execution resolves resolver-returned root field references through Resolution`() {
        val sdl =
            """
            extend type Query {
                probe: String @resolver
                referencedItem(id: Int!): Item @resolver
                catalog: Catalog
            }

            type Catalog @namespaceType {
                selectiveItem(id: Int!): Item @resolver
            }

            type Item {
                value: String
                unselected: String
            }
            """.trimIndent()
        val targetCalls = AtomicInteger()
        val suppliedModule =
            EngineTestModule(sdl) {
                field("Query" to "referencedItem") {
                    resolver {
                        fn { arguments, _, _, _, context ->
                            context.createRootFieldReference(
                                rootFieldPath = listOf("catalog", "selectiveItem"),
                                type = schema.schema.getObjectType("Item"),
                                args = mapOf("id" to arguments.getValue("id")),
                            )
                        }
                    }
                }
                field("Catalog" to "selectiveItem") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            resolverId = resolverId,
                            isSelective = true,
                        ) { arguments, _, _, selections, _ ->
                            targetCalls.incrementAndGet()
                            assertEquals(listOf("value"), requireNotNull(selections).selections().map { it.fieldName })
                            mapOf("value" to "selective-${arguments.getValue("id")}")
                        }
                    }
                }
                field("Query" to "probe") {
                    resolver {
                        fn { _, _, _, _, context ->
                            val handle = requireNotNull(context.executionHandle)
                            val querySelections =
                                context.engineSelectionSetFactory.engineSelectionSet(
                                    "Query",
                                    "referencedItem(id: 4) { value }",
                                    emptyMap(),
                                )
                            val queryResult =
                                context.engine.resolveSelectionSet(
                                    handle,
                                    querySelections,
                                    ResolveSelectionSetOptions.DEFAULT,
                                )
                            val item = queryResult.get("referencedItem") as EngineObjectData.Sync
                            item.get("value")
                        }
                    }
                }
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(
                        operationText = "{ probe referencedItem(id: 7) { value } }",
                    ),
                    SchemaId.Base,
                )
            }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(
            mapOf(
                "probe" to "selective-4",
                "referencedItem" to mapOf("value" to "selective-7"),
            ),
            result.getData(),
        )
        assertEquals(2, targetCalls.get())
    }

    @Test
    fun `engine2 exposes resolver-owned selections through its Engine API context`() {
        val sdl =
            """
            extend type Query {
                listing: Listing @resolver
            }

            type Listing {
                title: String
                price: Int @resolver
            }
            """.trimIndent()
        val suppliedModule =
            EngineTestModule(sdl) {
                field("Query" to "listing") {
                    resolverExecutor {
                        MockFieldUnbatchedResolverExecutor(
                            resolverId = resolverId,
                            isSelective = true,
                        ) { _, _, _, selections, context ->
                            val owned =
                                context.projectOwnedSelections(
                                    requireNotNull(selections),
                                    ResolverType.FIELD,
                                )
                            assertEquals(listOf("title"), owned.selections().map { it.fieldName })
                            mapOf("title" to "owned")
                        }
                    }
                }
                fieldWithValue("Listing" to "price", 9L)
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(
                        operationText = "{ listing { title price } }",
                    ),
                    SchemaId.Base,
                )
            }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(
            mapOf("listing" to mapOf("title" to "owned", "price" to 9)),
            result.getData(),
        )
    }

    @Test
    fun `engine2 executes production providers and checker dispatchers`() {
        val sdl =
            """
            extend type Query {
                item: Item @resolver
            }

            type Item {
                allowed(value: Int!): String @resolver
                secret(multiplier: Int!): String @resolver
            }
            """.trimIndent()
        val fieldResolverProviderCalls = AtomicInteger()
        val fieldCheckerProviderCalls = AtomicInteger()
        val fieldCheckerCalls = AtomicInteger()
        val typeCheckerProviderCalls = AtomicInteger()
        val typeCheckerCalls = AtomicInteger()
        val requestContext = Any()
        val suppliedModule =
            EngineTestModule(sdl) {
                fieldWithValue("Query" to "item", emptyMap<String, Any?>())
                field("Item" to "allowed") {
                    resolver {
                        fn { arguments, _, _, _, _ -> "allowed-${arguments.getValue("value")}" }
                    }
                }
                field("Item" to "secret") {
                    resolver {
                        objectSelections("resolverAllowed: allowed(value: \$provided)") {
                            variables("provided") { invocation, context ->
                                assertSame(requestContext, context.requestContext)
                                fieldResolverProviderCalls.incrementAndGet()
                                val multiplier = invocation.arguments.getValue("multiplier") as Int
                                mapOf("provided" to multiplier + 1)
                            }
                        }
                        fn { _, objectValue, _, _, _ -> objectValue.get("resolverAllowed") }
                    }
                    checker {
                        objectSelections("object", "checkerAllowed: allowed(value: \$provided)") {
                            variables("provided") { invocation, context ->
                                assertSame(requestContext, context.requestContext)
                                fieldCheckerProviderCalls.incrementAndGet()
                                mapOf("provided" to invocation.arguments.getValue("multiplier"))
                            }
                        }
                        fn { _, inputs ->
                            assertEquals("allowed-5", inputs.getValue("object").get("checkerAllowed"))
                            fieldCheckerCalls.incrementAndGet()
                        }
                    }
                }
                type("Item") {
                    checker {
                        objectSelections("object", "typeAllowed: allowed(value: \$provided)") {
                            variables("provided") { _, context ->
                                assertSame(requestContext, context.requestContext)
                                typeCheckerProviderCalls.incrementAndGet()
                                mapOf("provided" to 9)
                            }
                        }
                        fn { _, inputs ->
                            assertEquals("allowed-9", inputs.getValue("object").get("typeAllowed"))
                            typeCheckerCalls.incrementAndGet()
                        }
                    }
                }
            }
        val viaduct =
            StandardViaduct.Builder()
                .withTenantModuleInjectorFactory(
                    MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                .withCheckerExecutorFactory(
                    MockCheckerExecutorFactory(
                        suppliedModule.checkerExecutors,
                        suppliedModule.typeCheckerExecutors,
                    ),
                ).withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withFlagManager(
                    object : FlagManager {
                        override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                    },
                ).build()

        val result =
            runBlocking {
                viaduct.execute(
                    ExecutionInput.create(
                        operationText = "{ item { secret(multiplier: 5) } }",
                        requestContext = requestContext,
                    ),
                    SchemaId.Base,
                )
            }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("item" to mapOf("secret" to "allowed-6")), result.getData())
        assertEquals(1, fieldResolverProviderCalls.get())
        assertEquals(1, fieldCheckerProviderCalls.get())
        assertEquals(1, fieldCheckerCalls.get())
        assertEquals(1, typeCheckerProviderCalls.get())
        assertEquals(1, typeCheckerCalls.get())
    }
}
