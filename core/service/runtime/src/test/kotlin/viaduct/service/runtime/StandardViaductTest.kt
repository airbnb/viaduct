@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.bootstrap.ExecutionRegistryConfigFile
import viaduct.bootstrap.FieldEntryConfig
import viaduct.bootstrap.NodeEntryConfig
import viaduct.engine.EngineImpl
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.PendingEntry
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.IncrementalExecutionResultImpl
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.execution.UpdateExecutionResultImpl
import viaduct.engine.runtime2.Engine2
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult as ServiceExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.service.api.spi.mocks.MockFlagManager

class StandardViaductTest {
    private lateinit var subject: StandardViaduct
    private lateinit var dataFetcherExceptionHandler: DataFetcherExceptionHandler
    private lateinit var flagManager: FlagManager
    private val SCHEMA_ID = ""

    @BeforeEach
    fun setUp() {
        flagManager = mockk()
        every { flagManager.isEnabled(any()) } returns false
        dataFetcherExceptionHandler = mockk()
    }

    private fun createSimpleStandardViaduct() {
        createStandardViaduct()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createStandardViaduct() {
        // Create a basic schema for testing
        val sdl =
            """
                extend type Query {
                    test: String
                }
            """

        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        subject = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withDataFetcherExceptionHandler(dataFetcherExceptionHandler)
            .withSchemaConfiguration(schemaConfiguration)
            .build()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    internal class SuccessfulExecutionResult : ExecutionResult {
        override fun getErrors(): MutableList<GraphQLError> = mutableListOf()

        override fun <T : Any> getData(): T? = null

        override fun isDataPresent() = true

        override fun getExtensions(): MutableMap<Any, Any> = mutableMapOf()

        override fun toSpecification(): MutableMap<String, Any> = mutableMapOf()
    }

    @Test
    fun `engine selection uses construction-time flags when engines are created lazily`() {
        val sdl = "extend type Query { value: String @resolver }"
        val suppliedModule = EngineTestModule(sdl) { fieldWithValue("Query" to "value", "value") }

        fun buildWithMutableFlag(initiallyEnabled: Boolean): Pair<StandardViaduct, MutableEngine2FlagManager> {
            val mutableFlagManager = MutableEngine2FlagManager(initiallyEnabled)
            val viaduct =
                StandardViaduct.Builder()
                    .withTenantModuleInjectorFactory(
                        MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry),
                    ).withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
                    .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                    .withFlagManager(mutableFlagManager)
                    .build()
            return viaduct to mutableFlagManager
        }

        val (engine2Viaduct, engine2Flags) = buildWithMutableFlag(initiallyEnabled = true)
        engine2Flags.engine2Enabled = false
        assertTrue(engine2Viaduct.engineRegistry.getEngine(SchemaId.Base) is Engine2)

        val (legacyViaduct, legacyFlags) = buildWithMutableFlag(initiallyEnabled = false)
        legacyFlags.engine2Enabled = true
        assertTrue(legacyViaduct.engineRegistry.getEngine(SchemaId.Base) is EngineImpl)
    }

    @Test
    fun `sortExecutionResult sorts errors and includes null data for request errors`() {
        createStandardViaduct()
        val errors = listOf(
            GraphQLError.newError().message("z").path(listOf("z")).build(),
            GraphQLError.newError().message("b").path(listOf("a")).build(),
            GraphQLError.newError().message("a").path(listOf("a")).build(),
        )
        val result = graphql.ExecutionResult.newExecutionResult().errors(errors).extensions(mapOf("trace" to "id")).build()

        val sorted = subject.sortExecutionResult(result)

        assertEquals(listOf(errors[2], errors[1], errors[0]), sorted.errors)
        assertTrue(sorted.isDataPresent)
        assertTrue(sorted.toSpecification().containsKey("data"))
        assertNull(sorted.toSpecification()["data"])
        assertEquals(result.extensions, sorted.extensions)
    }

    @Test
    fun `execute and executeAsync wait for deferred fields and return complete responses`() =
        runBlocking {
            withTimeout(5.seconds) {
                for (asynchronous in listOf(false, true)) {
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val viaduct = streamingViaduct {
                        started.complete(Unit)
                        release.await()
                        "later"
                    }
                    val input = ExecutionInput.create("{ __typename ... @defer { test } }", requestContext = Any())
                    val result = async {
                        if (asynchronous) viaduct.executeAsync(input).await() else viaduct.execute(input)
                    }
                    started.await()
                    assertEquals(false, result.isCompleted)
                    release.complete(Unit)
                    assertEquals(
                        mapOf("data" to mapOf("__typename" to "Query", "test" to "later")),
                        result.await().toSpecification(),
                    )
                }
            }
        }

    @Test
    fun `reactiveExecute delivers initial data before deferred work completes`() =
        runBlocking<Unit> {
            withTimeout(5.seconds) {
                var calls = 0
                val release = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct {
                    calls++
                    release.await()
                    "later"
                }
                val input = ExecutionInput.create("{ __typename ... @defer(label: \"details\") { test } }", requestContext = Any())
                val results = viaduct.reactiveExecute(input).onEach { result ->
                    if (result is ServiceExecutionResult.Initial) {
                        assertEquals(mapOf("__typename" to "Query"), result.getData())
                        assertTrue(result.hasNext)
                        assertEquals(false, release.isCompleted)
                        release.complete(Unit)
                    }
                }.toList()
                assertEquals(1, calls)
                assertEquals(
                    listOf(
                        mapOf(
                            "data" to mapOf("__typename" to "Query"),
                            "pending" to listOf(mapOf("id" to "0", "path" to emptyList<Any>(), "label" to "details")),
                            "hasNext" to true,
                        ),
                        mapOf(
                            "incremental" to listOf(mapOf("id" to "0", "data" to mapOf("test" to "later"))),
                            "completed" to listOf(mapOf("id" to "0")),
                            "hasNext" to false,
                        ),
                    ),
                    results.map { it.toSpecification() },
                )
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reactiveExecute delivers multiple updates across the coroutine interop boundary`(useConfiguredExecutor: Boolean) {
        Executors.newSingleThreadExecutor { Thread(it, "reactive-execution-test") }.asCoroutineDispatcher().use { dispatcher ->
            runBlocking {
                withTimeout(5.seconds) {
                    val collectorThread = Thread.currentThread()
                    val executionThread = withContext(dispatcher) { Thread.currentThread() }
                    val releases = mapOf("first" to CompletableDeferred<Unit>(), "second" to CompletableDeferred<Unit>())
                    val sdl = "extend type Query { initial: String @resolver, first: String @resolver, second: String @resolver }"
                    val module = EngineTestModule(sdl) {
                        for (fieldName in listOf("initial", "first", "second")) {
                            field("Query" to fieldName) {
                                resolverExecutor {
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.$fieldName") { _, _, _, _, _ ->
                                        if (useConfiguredExecutor) assertSame(executionThread, Thread.currentThread())
                                        releases[fieldName]?.await()
                                        if (useConfiguredExecutor) assertSame(executionThread, Thread.currentThread())
                                        fieldName
                                    }
                                }
                            }
                        }
                    }
                    val interop = if (useConfiguredExecutor) {
                        object : CoroutineInterop by DefaultCoroutineInterop {
                            override fun <T> enterThreadLocalCoroutineContext(
                                callerContext: CoroutineContext,
                                block: suspend CoroutineScope.() -> T,
                            ): CompletableFuture<T> = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(dispatcher.executor, callerContext, block)
                        }
                    } else {
                        DefaultCoroutineInterop
                    }
                    val viaduct = StandardViaduct.Builder()
                        .withFlagManager(MockFlagManager.create(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION))
                        .withCoroutineInterop(interop)
                        .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
                        .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
                        .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                        .build()
                    val expected = listOf(
                        mapOf(
                            "data" to mapOf("initial" to "initial"),
                            "pending" to listOf(
                                mapOf("id" to "0", "path" to emptyList<Any>(), "label" to "first"),
                                mapOf("id" to "1", "path" to emptyList<Any>(), "label" to "second"),
                            ),
                            "hasNext" to true,
                        ),
                        mapOf(
                            "incremental" to listOf(mapOf("id" to "0", "data" to mapOf("first" to "first"))),
                            "completed" to listOf(mapOf("id" to "0")),
                            "hasNext" to true,
                        ),
                        mapOf(
                            "incremental" to listOf(mapOf("id" to "1", "data" to mapOf("second" to "second"))),
                            "completed" to listOf(mapOf("id" to "1")),
                            "hasNext" to false,
                        ),
                    )
                    val input = ExecutionInput.create(
                        "{ initial ... @defer(label: \"first\") { first } ... @defer(label: \"second\") { second } }",
                        requestContext = Any(),
                    )
                    var received = 0
                    viaduct.reactiveExecute(input).collect { result ->
                        assertSame(collectorThread, Thread.currentThread())
                        assertEquals(expected[received], result.toSpecification())
                        when (received++) {
                            0 -> {
                                assertFalse(releases.getValue("first").isCompleted)
                                releases.getValue("first").complete(Unit)
                            }
                            1 -> {
                                assertFalse(releases.getValue("second").isCompleted)
                                releases.getValue("second").complete(Unit)
                            }
                        }
                    }
                    assertEquals(expected.size, received)
                }
            }
        }
    }

    @Test
    fun `reactiveExecute propagates upstream failure while updates are buffered`() =
        runBlocking {
            withTimeout(5.seconds) {
                val failure = IllegalStateException("deferred execution failed")
                val updatesBuffered = CompletableDeferred<Unit>()
                val failUpdates = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val result = IncrementalExecutionResultImpl(
                    initialResult = ExecutionResult.newExecutionResult().data(mapOf("__typename" to "Query")).build(),
                    pending = listOf(PendingEntry("0", ResultPath.rootPath(), "details")),
                    hasNext = true,
                    updates = flow {
                        for (name in listOf("first", "second")) {
                            emit(
                                UpdateExecutionResultImpl(
                                    hasNext = true,
                                    incremental = listOf(IncrementalEntry.Object("0", mapOf(name to name), emptyList(), null)),
                                    completed = emptyList(),
                                    pending = emptyList(),
                                )
                            )
                        }
                        updatesBuffered.complete(Unit)
                        failUpdates.await()
                        throw failure
                    },
                    cancelExecution = { cancelled.complete(Unit) },
                )
                val engine = mockk<Engine>()
                coEvery { engine.execute(any(), incrementalExecution = true) } returns result
                val registry = mockk<EngineRegistry>()
                every { registry.getEngine(SchemaId.Base) } returns engine
                val viaduct = StandardViaduct(registry, standardViaductFactory = mockk())
                val received = mutableListOf<ServiceExecutionResult>()

                val thrown = assertThrows<IllegalStateException> {
                    viaduct.reactiveExecute(ExecutionInput.create("{ __typename ... @defer { first second } }", requestContext = Any()))
                        .buffer(capacity = 2)
                        .collect {
                            received += it
                            updatesBuffered.await()
                            failUpdates.complete(Unit)
                            awaitCancellation()
                        }
                }

                assertEquals(failure.message, thrown.message)
                val initial = assertInstanceOf(ServiceExecutionResult.Initial::class.java, received.single())
                assertEquals(mapOf("__typename" to "Query"), initial.getData())
                assertTrue(cancelled.isCompleted)
            }
        }

    @Test
    fun `reactiveExecute returns Standard when defer is disabled or absent`() =
        runBlocking {
            for (deferEnabled in listOf(false, true)) {
                val viaduct = streamingViaduct(deferEnabled) { "value" }
                val query = if (deferEnabled) "{ test }" else "{ ... @defer { test } }"
                val result = assertInstanceOf(
                    ServiceExecutionResult.Standard::class.java,
                    viaduct.reactiveExecute(ExecutionInput.create(query, requestContext = Any())).single(),
                )
                assertEquals(mapOf("data" to mapOf("test" to "value")), result.toSpecification())
            }
        }

    @Test
    fun `reactiveExecute returns request errors without resolving fields`() =
        runBlocking {
            var calls = 0
            val viaduct = streamingViaduct {
                calls++
                "value"
            }
            val input = ExecutionInput.create("{ test }", requestContext = Any())
            val results = listOf(
                viaduct.reactiveExecute(input, SchemaId.None).single(),
                viaduct.reactiveExecute(ExecutionInput.create("{ missingField }", requestContext = Any())).single(),
                viaduct.reactiveExecute(ExecutionInput.create("{", requestContext = Any())).single(),
            )
            for (payload in results) {
                val result = assertInstanceOf(ServiceExecutionResult.Standard::class.java, payload)
                assertTrue(result.errors.isNotEmpty())
                assertEquals(false, result.isDataPresent)
                assertEquals(false, result.toSpecification().containsKey("data"))
            }
            assertEquals(0, calls)
        }

    @Test
    fun `cancelling updates cancels deferred resolution`() =
        runBlocking {
            withTimeout(5.seconds) {
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                    viaduct.reactiveExecute(ExecutionInput.create("{ __typename ... @defer { test } }", requestContext = Any())).collect {}
                }
                started.await()
                collection.cancelAndJoin()
                cancelled.await()
            }
        }

    @Test
    fun `taking only the initial payload cancels deferred resolution`() =
        runBlocking {
            withTimeout(5.seconds) {
                val cancelled = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val initial = viaduct.reactiveExecute(ExecutionInput.create("{ __typename ... @defer { test } }", requestContext = Any()))
                    .onEach { started.await() }.first()
                assertInstanceOf(ServiceExecutionResult.Initial::class.java, initial)
                cancelled.await()
            }
        }

    @Test
    fun `collector failure cancels deferred resolution`() =
        runBlocking {
            withTimeout(5.seconds) {
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val failure = IllegalStateException("consumer failed")
                val viaduct = streamingViaduct {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val thrown = assertThrows<IllegalStateException> {
                    viaduct.reactiveExecute(ExecutionInput.create("{ ... @defer { test } }", requestContext = Any())).collect {
                        started.await()
                        throw failure
                    }
                }
                assertEquals(failure.message, thrown.message)
                cancelled.await()
            }
        }

    @Test
    fun `reactiveExecute is cold and each collection executes again`() =
        runBlocking {
            var calls = 0
            val viaduct = streamingViaduct { (++calls).toString() }
            val results = viaduct.reactiveExecute(ExecutionInput.create("{ test }", requestContext = Any()))
            assertEquals(0, calls)
            assertEquals(mapOf("test" to "1"), assertInstanceOf(ServiceExecutionResult.Standard::class.java, results.single()).getData())
            assertEquals(mapOf("test" to "2"), assertInstanceOf(ServiceExecutionResult.Standard::class.java, results.single()).getData())
        }

    @Test
    fun `cancelling reactiveExecute before the initial result cancels resolution`() =
        runBlocking {
            withTimeout(5.seconds) {
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                val execution = launch { viaduct.reactiveExecute(ExecutionInput.create("{ test }", requestContext = Any())).collect {} }
                started.await()
                execution.cancelAndJoin()
                cancelled.await()
            }
        }

    private fun streamingViaduct(
        deferEnabled: Boolean = true,
        resolve: suspend () -> String,
    ): StandardViaduct {
        val sdl = "extend type Query { test: String @resolver }"
        val module = EngineTestModule(sdl) {
            field("Query" to "test") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.test") { _, _, _, _, _ -> resolve() }
                }
            }
        }
        return StandardViaduct.Builder()
            .withFlagManager(
                MockFlagManager(if (deferEnabled) setOf(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION) else emptySet()),
            )
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `test registerScopedSchema from schema registry builder builder`() {
        val fullSchema = makeSchema(
            """
                extend type Query @scope(to: ["scope1"]) {
                  field1: String
                }

                extend type Query @scope(to: ["scope2"]) {
                  field2: String
                }

                type Foo implements Node @scope(to: ["*"]) { # Ensure Query.node/s get created
                  id: ID!
                }
            """.trimIndent()
        )

        val schemaId = SchemaId.Scoped(SCHEMA_ID, setOf("scope1"))
        val config = SchemaConfiguration.fromSchema(
            fullSchema,
            scopes = setOf(schemaId.toScopeConfig())
        )
        val viaductBuilder = StandardViaduct.Builder().withSchemaConfiguration(config)

        val stdViaduct = viaductBuilder.build()
        val queryType = stdViaduct.getSchema(schemaId).schema.typeMap["Query"] as GraphQLObjectType
        val queryFields = queryType.fieldDefinitions?.map { it.name }

        assertEquals(listOf("field1", "node", "nodes"), queryFields)
    }

    @Test
    fun `executeAsync returns error for missing schema`() {
        val query = "{ test }"
        val context = mapOf("userId" to "user123")
        val executionInput = ExecutionInput.create(operationText = query, requestContext = context)

        createSimpleStandardViaduct()

        runBlocking {
            val result = subject.executeAsync(executionInput, SchemaId.None).join()
            val errors = result.errors
            assertEquals(1, errors.size)
            assertEquals("Schema not found for schemaId=SchemaId(id='NONE')", errors.first().message)
            assertNull(result.getData())
        }
    }

    @Test
    fun `build should throw GraphQLBuildError when schema contains Subscription extension in OSS mode`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Subscription {
                userUpdated: String
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl, scopes = emptySet())

        val exception = assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals("Viaduct does not currently support subscriptions.", exception.message)
    }

    @Test
    fun `subscription validation uses the base view when Base is not registered`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Subscription {
                internalUpdate: String @tenantLocal
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl, scopes = emptySet())

        val viaduct = assertDoesNotThrow {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals(emptySet<SchemaId>(), viaduct.engineRegistry.getRegisteredSchemaIds())
    }

    @Test
    fun `an instance registering no views starts and then serves nothing`() {
        val sdl = """
            extend type Query {
                user: String
            }
        """.trimIndent()
        val viaduct = StandardViaduct.Builder()
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl, scopes = emptySet()))
            .build()

        listOf(SchemaId.Base, SchemaId.Scoped("publicSchema", setOf("default"))).forEach { schemaId ->
            val result = viaduct.executeAsync(ExecutionInput.create("query { user }"), schemaId).join()

            assertNull(result.getData(), "expected no data for $schemaId")
            assertEquals(1, result.errors.size, "expected one error for $schemaId: ${result.errors}")
            assertEquals("Schema not found for schemaId=$schemaId", result.errors.single().message)
        }
    }

    @Test
    fun `build should allow Subscription when airbnbModeEnabled is true`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Subscription {
                userUpdated: String
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertDoesNotThrow {
            StandardViaduct.Builder()
                .enableAirbnbBypassDoNotUse(tenantNameResolver = TenantNameResolver())
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    @Test
    fun `build should succeed when schema has no Subscriptions in OSS mode`() {
        val sdl = """
            extend type Query {
                user: String
            }

            extend type Mutation {
                updateUser: String
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertDoesNotThrow {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    @Test
    fun `build should throw GraphQLBuildError when resolver field has no registered executor`() {
        val sdl = """
            extend type Query {
                missing: String @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        val exception = assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals(true, exception.message?.contains("Query.missing"))
        assertEquals("MissingResolversException", exception.cause?.javaClass?.simpleName)
    }

    @Test
    fun `build should throw GraphQLBuildError when a namespace-type field is wrapped`() {
        // Built-in @namespaceType config generation runs during registry construction. A wrapped
        // (list/non-null) namespace field makes that generation throw; the failure must surface as a
        // GraphQLBuildError rather than leaking a raw Guice ProvisionException to the caller.
        val sdl = """
            type Listings @namespaceType {
                name: String
            }
            extend type Query {
                listings: [Listings]
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    @Test
    fun `build should validate tenant-local resolver fields against full schema`() {
        val sdl = """
            extend type Query {
                visible: String
                missingTenantLocal: String @tenantLocal @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        val exception = assertThrows<GraphQLBuildError> {
            StandardViaduct.Builder()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }

        assertEquals(true, exception.message?.contains("Query.missingTenantLocal"))
        assertEquals("MissingResolversException", exception.cause?.javaClass?.simpleName)
    }

    @Test
    fun `build should succeed with lenient resolver validation when resolver field has no executor`() {
        val sdl = """
            extend type Query {
                missing: String @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)

        assertDoesNotThrow {
            StandardViaduct.Builder()
                .withLenientResolverValidation()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
    }

    /** Ensures [TenantModuleInjectorFactory.finalize] is called when bootstrapping via the file-based path. */
    @Test
    fun `withTenantModuleInjectorFactory routes through file-based path and calls finalize`() {
        val recording = RecordingFinalizingTenantModuleInjectorFactory()

        val sdl = """
            extend type Query {
                test: String
            }
        """.trimIndent()

        StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(recording)
            .withExecutorRegistryConfigSources(emptyList())
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build()

        assertEquals(true, recording.finalized, "finalize() must be called via the file-based bootstrap path")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `caller supplied module config sources contribute resolvers`(engine2Enabled: Boolean) {
        val sdl = """
            extend type Query {
                generatedRegistryTestField: String @resolver
            }
        """.trimIndent()
        val suppliedModule = EngineTestModule(sdl) {
            fieldWithValue("Query" to "generatedRegistryTestField", "caller-supplied")
        }

        val viaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .withFlagManager(
                object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean = engine2Enabled && flag == FlagManager.Flags.ENGINE2_ENABLED
                },
            )
            .build()

        val result = runBlocking {
            viaduct.execute(
                ExecutionInput.create(
                    operationText = "{ generatedRegistryTestField }",
                    requestContext = Any(),
                ),
                SchemaId.Base,
            )
        }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("generatedRegistryTestField" to "caller-supplied"), result.getData())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `execution returns deferred fields when incremental execution is enabled`(asynchronous: Boolean) {
        val sdl = "extend type Query { value: Int @resolver }"
        val suppliedModule = EngineTestModule(sdl) { fieldWithValue("Query" to "value", 42) }
        every { flagManager.isEnabled(FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION) } returns true
        val viaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .withFlagManager(flagManager)
            .build()
        val input = ExecutionInput.create(
            operationText = "{ __typename ... @defer { value } }",
            requestContext = Any(),
        )

        val result = runBlocking {
            if (asynchronous) {
                viaduct.executeAsync(input, SchemaId.Base).join()
            } else {
                viaduct.execute(input, SchemaId.Base)
            }
        }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("__typename" to "Query", "value" to 42), result.getData())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `configured materialized reader reaches nested field execution`(customReader: Boolean) {
        val sdl = """
            extend type Query { outer: Outer @resolver }
            type Outer { inner: Inner inners: [Inner] }
            type Inner { name: String nullableName: String }
        """.trimIndent()
        val resolverCalls = AtomicInteger()
        val suppliedModule = EngineTestModule(sdl) {
            field("Query" to "outer") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                    ) { _, _, _, _, context ->
                        resolverCalls.incrementAndGet()
                        val inner = createEngineObjectData(context.fullSchema.schema.getObjectType("Inner"), mapOf("name" to "value", "nullableName" to null))
                        createEngineObjectData(context.fullSchema.schema.getObjectType("Outer"), mapOf("inner" to inner, "inners" to listOf(inner)))
                    }
                }
            }
        }
        val builder = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(suppliedModule.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(suppliedModule.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENABLE_MAT_RESOLUTION
            })
        if (customReader) {
            builder.withMaterializedFieldValueReader(
                MaterializedFieldValueReader { source, fieldName, responseKey ->
                    val read = MaterializedFieldValueReader.Default.read(source, fieldName, responseKey)
                    if (source.type.name == "Inner" && fieldName == "name") {
                        read.copy(value = "${read.value}:$responseKey")
                    } else {
                        read
                    }
                }
            )
        }
        val viaduct = builder.build()

        val result = runBlocking {
            viaduct.execute(
                ExecutionInput.create(
                    operationText = "{ outer { inner { selected: name nullableName } inners { other: name } } }",
                    requestContext = Any(),
                ),
                SchemaId.Base,
            )
        }

        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(
            mapOf(
                "outer" to mapOf(
                    "inner" to mapOf("selected" to if (customReader) "value:selected" else "value", "nullableName" to null),
                    "inners" to listOf(mapOf("other" to if (customReader) "value:other" else "value")),
                ),
            ),
            result.getData(),
        )
        assertEquals(1, resolverCalls.get())
    }

    @Test
    fun `buildWithReusedSchemas refreshes generated registry sources without rebuilding schemas`() {
        val sdl = """
            extend type Query {
                generatedRegistryTestField: String @resolver
            }
        """.trimIndent()
        val schemaConfiguration = SchemaConfiguration.fromSdl(sdl)
        val oldViaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(CodeInjector.Naive))
            .withExecutorRegistryConfigSources(
                listOf(generatedRegistryConfigSource(value = "old-registry"))
            )
            .withSchemaConfiguration(schemaConfiguration)
            .build()

        val newViaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(CodeInjector.Naive))
            .withExecutorRegistryConfigSources(
                listOf(generatedRegistryConfigSource(value = "new-registry"))
            )
            .withSchemaConfiguration(schemaConfiguration)
            .buildWithReusedSchemas(oldViaduct)
        val result = runBlocking {
            newViaduct.execute(
                ExecutionInput.create(
                    operationText = "{ generatedRegistryTestField }",
                    requestContext = Any(),
                ),
                SchemaId.Base,
            )
        }

        assertSame(oldViaduct.getSchema(SchemaId.Base), newViaduct.getSchema(SchemaId.Base))
        assertEquals(emptyList<GraphQLError>(), result.errors)
        assertEquals(mapOf("generatedRegistryTestField" to "new-registry"), result.getData())
    }
}

private class MutableEngine2FlagManager(
    var engine2Enabled: Boolean,
) : FlagManager {
    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED && engine2Enabled
}

private fun makeSchema(schema: String): EngineSchema {
    return EngineSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(schema).apply {
                DefaultSchemaFactory.addDefaults(this)
            }
        )
    )
}

/** Records whether [onBootstrapComplete] was invoked; used to verify the file-based bootstrap path fires. */
private class RecordingFinalizingTenantModuleInjectorFactory : TenantModuleInjectorFactory {
    var finalized: Boolean = false

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?
    ): CodeInjector = CodeInjector.Naive

    override suspend fun onBootstrapComplete() {
        finalized = true
    }
}

private fun generatedRegistryConfigSource(value: String): ModuleConfigSource =
    ModuleConfigSource.from(
        InputStreamSource.fromString(
            """
            {
              "version": "1",
              "tenantName": "generatedregistrytest",
              "apiName": "kotlin",
              "executorFactory": "${GeneratedRegistryTestExecutorFactory::class.java.name}",
              "nodes": [],
              "fields": [
                {
                  "typeName": "Query",
                  "fieldName": "generatedRegistryTestField",
                  "isBatching": false,
                  "isSelective": false,
                  "attribution": "generated-registry-test",
                  "tenantAPIData": {
                    "value": "$value"
                  }
                }
              ]
            }
            """.trimIndent(),
            name = "generated-registry-$value",
        ),
    )

class GeneratedRegistryTestExecutorFactory(
    @Suppress("UNUSED_PARAMETER") injector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: EngineSchema
    ): FieldResolverExecutor =
        MockFieldUnbatchedResolverExecutor(
            resolverId = "${configData.typeName}.${configData.fieldName}",
        ) { _, _, _, _, _ -> configData.tenantAPIData["value"] as String }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: EngineSchema
    ): NodeResolverExecutor {
        throw UnsupportedOperationException("Node resolvers are not used by this test")
    }
}
