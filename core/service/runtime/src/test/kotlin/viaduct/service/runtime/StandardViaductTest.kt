@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.apiannotations.InternalApi
import viaduct.bootstrap.ExecutionRegistryConfigFile
import viaduct.bootstrap.FieldEntryConfig
import viaduct.bootstrap.NodeEntryConfig
import viaduct.engine.EngineImpl
import viaduct.engine.EngineKind
import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ExecutionInput as EngineExecutionInput
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
import viaduct.service.api.IncrementalEntry as ServiceIncrementalEntry
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
    fun `per-request mode runs each request on the engine chosen by USE_ENGINE2`() {
        val flags = EngineFlags(perRequest = true)
        val viaduct = contextPackageViaductBuilder().withFlagManager(flags).build()

        flags.useEngine2 = true
        val engine2Package = viaduct.executeContextPackageQuery("OnEngine2")
        flags.useEngine2 = false
        val currentPackage = viaduct.executeContextPackageQuery("OnCurrent")

        assertTrue(engine2Package.startsWith(RUNTIME2_PACKAGE), engine2Package)
        assertFalse(currentPackage.startsWith(RUNTIME2_PACKAGE), currentPackage)
    }

    @Test
    fun `per-request mode runs each reactive request on the engine chosen by USE_ENGINE2`() {
        val flags = EngineFlags(perRequest = true)
        val viaduct = contextPackageViaductBuilder().withFlagManager(flags).build()

        flags.useEngine2 = true
        val engine2Package = viaduct.reactiveExecuteContextPackageQuery("OnEngine2")
        flags.useEngine2 = false
        val currentPackage = viaduct.reactiveExecuteContextPackageQuery("OnCurrent")

        assertTrue(engine2Package.startsWith(RUNTIME2_PACKAGE), engine2Package)
        assertFalse(currentPackage.startsWith(RUNTIME2_PACKAGE), currentPackage)
    }

    @Test
    fun `ENGINE2_ENABLED runs every request on Engine2 even in per-request mode`() {
        val viaduct = contextPackageViaductBuilder()
            .withFlagManager(EngineFlags(engine2Enabled = true, perRequest = true, useEngine2 = false))
            .build()

        val contextPackage = viaduct.executeContextPackageQuery("Query")

        assertTrue(contextPackage.startsWith(RUNTIME2_PACKAGE), contextPackage)
    }

    @Test
    fun `USE_ENGINE2 is ignored outside per-request mode`() {
        val viaduct = contextPackageViaductBuilder()
            .withFlagManager(EngineFlags(useEngine2 = true))
            .build()

        val contextPackage = viaduct.executeContextPackageQuery("Query")

        assertFalse(contextPackage.startsWith(RUNTIME2_PACKAGE), contextPackage)
    }

    @Test
    fun `graphql-java entry points use the current engine in per-request mode`() {
        val viaduct = contextPackageViaductBuilder()
            .withFlagManager(EngineFlags(perRequest = true, useEngine2 = true))
            .build()

        assertDoesNotThrow { viaduct.getEngine(SchemaId.Base) }
        assertDoesNotThrow { viaduct.engineRegistry.getGraphQLEngine_DONOTUSE(SchemaId.Base) }
        assertDoesNotThrow { viaduct.createEngineExecutionContext(SchemaId.Base, null) }
        assertInstanceOf(EngineImpl::class.java, viaduct.engineRegistry.getEngine(SchemaId.Base))
    }

    @Test
    @OptIn(InternalApi::class)
    fun `requests choose their own engine only in per-request mode`() {
        fun choosesEnginePerRequest(flags: EngineFlags) = contextPackageViaductBuilder().withFlagManager(flags).build().choosesEnginePerRequest

        assertTrue(choosesEnginePerRequest(EngineFlags(perRequest = true)))
        assertFalse(choosesEnginePerRequest(EngineFlags()))
        assertFalse(choosesEnginePerRequest(EngineFlags(engine2Enabled = true, perRequest = true)))
    }

    @Test
    @OptIn(InternalApi::class)
    fun `graphql-java entry points serve the engine of the given kind`() {
        val viaduct = contextPackageViaductBuilder().withFlagManager(EngineFlags(perRequest = true)).build()

        assertSame(viaduct.engineRegistry.getEngine(SchemaId.Base, EngineKind.ENGINE2).graphQL(), viaduct.getEngine(SchemaId.Base, EngineKind.ENGINE2))
        assertSame(viaduct.engineRegistry.getEngine(SchemaId.Base, EngineKind.CURRENT).graphQL(), viaduct.getEngine(SchemaId.Base, EngineKind.CURRENT))
        assertSame(viaduct.engineRegistry.getFullSchemaEngine(EngineKind.ENGINE2).graphQL(), viaduct.getFullSchemaEngine(EngineKind.ENGINE2))
        assertTrue(viaduct.engineRegistry.getFullSchemaEngine(EngineKind.ENGINE2) is Engine2)
        assertSame(viaduct.engineRegistry.getFullSchemaEngine(EngineKind.CURRENT).graphQL(), viaduct.getFullSchemaEngine(EngineKind.CURRENT))
        // Without a request context, the per-engine overload still wins over the default-engine one.
        val engine2ContextPackage = viaduct.createEngineExecutionContext(SchemaId.Base, EngineKind.ENGINE2).javaClass.packageName
        val currentContextPackage = viaduct.createEngineExecutionContext(SchemaId.Base, EngineKind.CURRENT, null).javaClass.packageName
        assertTrue(engine2ContextPackage.startsWith(RUNTIME2_PACKAGE), engine2ContextPackage)
        assertFalse(currentContextPackage.startsWith(RUNTIME2_PACKAGE), currentContextPackage)
    }

    @Test
    @OptIn(InternalApi::class)
    fun `per-request mode is fixed when the Viaduct is built`() {
        val flags = EngineFlags()
        val viaduct = contextPackageViaductBuilder().withFlagManager(flags).build()

        flags.perRequest = true
        flags.useEngine2 = true
        val contextPackage = viaduct.executeContextPackageQuery("Query")

        assertFalse(contextPackage.startsWith(RUNTIME2_PACKAGE), contextPackage)
        assertFalse(viaduct.choosesEnginePerRequest)

        val perRequestFlags = EngineFlags(perRequest = true)
        val perRequestViaduct = contextPackageViaductBuilder().withFlagManager(perRequestFlags).build()
        perRequestFlags.engine2Enabled = true

        assertTrue(perRequestViaduct.choosesEnginePerRequest)
    }

    private fun contextPackageViaductBuilder(): StandardViaduct.Builder {
        val sdl = "extend type Query { contextPackage: String @resolver }"
        val module = EngineTestModule(sdl) {
            field("Query" to "contextPackage") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(resolverId = resolverId) { _, _, _, _, context ->
                        context.javaClass.packageName
                    }
                }
            }
        }
        return StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
    }

    private fun StandardViaduct.executeContextPackageQuery(operationName: String): String {
        val result = runBlocking {
            execute(
                ExecutionInput.create(operationText = "query $operationName { contextPackage }", operationName = operationName, requestContext = Any()),
                SchemaId.Base,
            )
        }
        assertEquals(emptyList<GraphQLError>(), result.errors)
        return result.getData()!!.getValue("contextPackage").toString()
    }

    private fun StandardViaduct.reactiveExecuteContextPackageQuery(operationName: String): String {
        val result = runBlocking {
            reactiveExecute(
                ExecutionInput.create(operationText = "query $operationName { contextPackage }", operationName = operationName, requestContext = Any()),
                SchemaId.Base,
            ).toList().single()
        }
        val data = result.toSpecification()["data"] as Map<*, *>
        return data["contextPackage"].toString()
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

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `execute and executeAsync wait for deferred fields and return complete responses`(
        asynchronous: Boolean,
        engine2Enabled: Boolean,
    ): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct(engine2Enabled = engine2Enabled) {
                    started.complete(Unit)
                    release.await()
                    "later"
                }
                val input = ExecutionInput.create("{ __typename ... @defer { test } }", requestContext = Any())
                val execution = async {
                    if (asynchronous) viaduct.executeAsync(input).await() else viaduct.execute(input)
                }
                try {
                    started.await()
                    assertFalse(execution.isCompleted)
                    release.complete(Unit)
                    assertEquals(
                        mapOf("data" to mapOf("__typename" to "Query", "test" to "later")),
                        execution.await().toSpecification(),
                    )
                } finally {
                    release.complete(Unit)
                    execution.cancelAndJoin()
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `execute and executeAsync forward inputs and request complete execution`(asynchronous: Boolean): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val requestContext = Any()
                val input = ExecutionInput.builder()
                    .operationText("query Selected { ... @defer { test } }")
                    .operationName("Selected")
                    .operationId("operation-id")
                    .variables(mapOf("value" to "value"))
                    .executionId("execution-id")
                    .requestContext(requestContext)
                    .build()
                val captured = slot<EngineExecutionInput>()
                val engine = mockk<Engine>()
                coEvery { engine.execute(capture(captured), incrementalExecution = false) } returns
                    ExecutionResult.newExecutionResult().data(mapOf("test" to "value")).build()
                val registry = mockk<EngineRegistry>()
                every { registry.getEngine(SchemaId.Base, EngineKind.CURRENT) } returns engine
                val viaduct = StandardViaduct(registry, standardViaductFactory = mockk(), flagManager = FlagManager.Disabled)

                val result = if (asynchronous) viaduct.executeAsync(input).await() else viaduct.execute(input)

                coVerify(exactly = 1) { engine.execute(any(), incrementalExecution = false) }
                assertEquals(
                    EngineExecutionInput(
                        operationText = input.operationText,
                        operationName = "Selected",
                        operationId = "operation-id",
                        variables = mapOf("value" to "value"),
                        executionId = "execution-id",
                        requestContext = requestContext,
                    ),
                    captured.captured,
                )
                assertSame(requestContext, captured.captured.requestContext)
                assertEquals(mapOf("data" to mapOf("test" to "value")), result.toSpecification())
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reactiveExecute forwards inputs and permits incremental execution regardless of capability`(supportsIncrementalExecution: Boolean): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val requestContext = Any()
                val schemaId = SchemaId.Scoped("test", setOf("test"))
                val input = ExecutionInput.builder()
                    .operationText("query Selected { ... @defer { test } }")
                    .operationName("Selected")
                    .operationId("operation-id")
                    .variables(mapOf("value" to "value"))
                    .executionId("execution-id")
                    .requestContext(requestContext)
                    .build()
                val captured = slot<EngineExecutionInput>()
                val engine = mockk<Engine>()
                every { engine.supportsIncrementalExecution } returns supportsIncrementalExecution
                coEvery { engine.execute(capture(captured), incrementalExecution = true) } returns
                    ExecutionResult.newExecutionResult().data(mapOf("test" to "value")).build()
                val registry = mockk<EngineRegistry>()
                every { registry.getEngine(schemaId, EngineKind.CURRENT) } returns engine
                val viaduct = StandardViaduct(registry, standardViaductFactory = mockk(), flagManager = FlagManager.Disabled)

                val result = assertInstanceOf(ServiceExecutionResult.Standard::class.java, viaduct.reactiveExecute(input, schemaId).single())

                coVerify(exactly = 1) { engine.execute(any(), incrementalExecution = true) }
                assertEquals(
                    EngineExecutionInput(
                        operationText = input.operationText,
                        operationName = "Selected",
                        operationId = "operation-id",
                        variables = mapOf("value" to "value"),
                        executionId = "execution-id",
                        requestContext = requestContext,
                    ),
                    captured.captured,
                )
                assertSame(requestContext, captured.captured.requestContext)
                assertEquals(mapOf("data" to mapOf("test" to "value")), result.toSpecification())
            }
        }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `reactiveExecute delivers results across the coroutine interop boundary`(
        useConfiguredExecutor: Boolean,
        engine2Enabled: Boolean
    ) {
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
                        .withFlagManager(
                            MockFlagManager(
                                setOfNotNull(
                                    FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION,
                                    FlagManager.Flags.ENGINE2_ENABLED.takeIf { engine2Enabled },
                                ),
                            ),
                        )
                        .withCoroutineInterop(interop)
                        .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
                        .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
                        .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                        .build()
                    val engine = viaduct.engineRegistry.getEngine(SchemaId.Base)
                    assertInstanceOf(if (engine2Enabled) Engine2::class.java else EngineImpl::class.java, engine)
                    val incremental = engine.supportsIncrementalExecution
                    val input = ExecutionInput.create(
                        "{ initial ... @defer(label: \"first\") { first } ... @defer(label: \"second\") { second } }",
                        requestContext = Any(),
                    )
                    val delivered = mutableListOf<String>()
                    if (!incremental) releases.values.forEach { it.complete(Unit) }
                    try {
                        viaduct.reactiveExecute(input).collect { result ->
                            assertSame(collectorThread, Thread.currentThread())
                            when (result) {
                                is ServiceExecutionResult.Initial -> {
                                    assertTrue(incremental)
                                    assertTrue(result.errors.isEmpty())
                                    assertEquals(mapOf("initial" to "initial"), result.getData())
                                    assertTrue(releases.values.none { it.isCompleted })
                                    delivered += "initial"
                                    releases.getValue("first").complete(Unit)
                                }
                                is ServiceExecutionResult.Update -> {
                                    val name = listOf("first", "second")[delivered.size - 1]
                                    val entry = assertInstanceOf(ServiceIncrementalEntry.Object::class.java, result.incremental.single())
                                    assertEquals(mapOf(name to name), entry.data)
                                    assertTrue(entry.errors.isEmpty())
                                    assertEquals(name == "first", result.hasNext)
                                    delivered += name
                                    if (name == "first") releases.getValue("second").complete(Unit)
                                }
                                is ServiceExecutionResult.Standard -> {
                                    assertFalse(incremental)
                                    assertTrue(result.errors.isEmpty())
                                    assertEquals(mapOf("initial" to "initial", "first" to "first", "second" to "second"), result.getData())
                                    delivered += "complete"
                                }
                            }
                        }
                        assertEquals(if (incremental) listOf("initial", "first", "second") else listOf("complete"), delivered)
                    } finally {
                        releases.values.forEach { it.complete(Unit) }
                    }
                }
            }
        }
    }

    @Test
    fun `reactiveExecute propagates upstream failure while updates are buffered`(): Unit =
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
                every { registry.getEngine(SchemaId.Base, EngineKind.CURRENT) } returns engine
                val viaduct = StandardViaduct(registry, standardViaductFactory = mockk(), flagManager = FlagManager.Disabled)
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
    fun `reactiveExecute reports missing schemas without resolving fields`(): Unit =
        runBlocking {
            var calls = 0
            val viaduct = streamingViaduct {
                calls++
                "value"
            }

            val result = assertInstanceOf(
                ServiceExecutionResult.Standard::class.java,
                viaduct.reactiveExecute(ExecutionInput.create("{ test }", requestContext = Any()), SchemaId.None).single(),
            )

            assertTrue(result.errors.isNotEmpty())
            assertFalse(result.isDataPresent)
            assertFalse(result.toSpecification().containsKey("data"))
            assertEquals(0, calls)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelling updates cancels deferred resolution`(engine2Enabled: Boolean): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val initialReceived = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct(engine2Enabled = engine2Enabled) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                assumeTrue(viaduct.engineRegistry.getEngine(SchemaId.Base).supportsIncrementalExecution)
                val collection = launch(start = CoroutineStart.UNDISPATCHED) {
                    viaduct.reactiveExecute(ExecutionInput.create("{ __typename ... @defer { test } }", requestContext = Any())).collect {
                        assertInstanceOf(ServiceExecutionResult.Initial::class.java, it)
                        initialReceived.complete(Unit)
                    }
                }
                initialReceived.await()
                started.await()
                collection.cancelAndJoin()
                cancelled.await()
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `taking only the initial payload cancels deferred resolution`(engine2Enabled: Boolean): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val cancelled = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct(engine2Enabled = engine2Enabled) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                assumeTrue(viaduct.engineRegistry.getEngine(SchemaId.Base).supportsIncrementalExecution)
                val initial = viaduct.reactiveExecute(ExecutionInput.create("{ __typename ... @defer { test } }", requestContext = Any()))
                    .onEach { started.await() }.first()
                assertInstanceOf(ServiceExecutionResult.Initial::class.java, initial)
                cancelled.await()
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `collector failure cancels deferred resolution`(engine2Enabled: Boolean): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val failure = IllegalStateException("consumer failed")
                val viaduct = streamingViaduct(engine2Enabled = engine2Enabled) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                assumeTrue(viaduct.engineRegistry.getEngine(SchemaId.Base).supportsIncrementalExecution)
                val thrown = assertThrows<IllegalStateException> {
                    viaduct.reactiveExecute(ExecutionInput.create("{ ... @defer { test } }", requestContext = Any())).collect {
                        assertInstanceOf(ServiceExecutionResult.Initial::class.java, it)
                        started.await()
                        throw failure
                    }
                }
                assertEquals(failure.message, thrown.message)
                cancelled.await()
            }
        }

    @Test
    fun `reactiveExecute is cold and each collection forwards initial and update payloads`(): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                var calls = 0
                var cancellations = 0
                val engine = mockk<Engine>()
                coEvery { engine.execute(any(), incrementalExecution = true) } coAnswers {
                    val value = ++calls
                    IncrementalExecutionResultImpl(
                        initialResult = ExecutionResult.newExecutionResult().data(mapOf("value" to value)).build(),
                        pending = listOf(PendingEntry("deferred", ResultPath.rootPath(), "details")),
                        hasNext = true,
                        updates = flow {
                            emit(
                                UpdateExecutionResultImpl(
                                    hasNext = false,
                                    incremental = listOf(IncrementalEntry.Object("deferred", mapOf("later" to value), emptyList(), null)),
                                    completed = listOf(CompletedEntry("deferred", emptyList())),
                                    pending = emptyList(),
                                ),
                            )
                        },
                        cancelExecution = { cancellations++ },
                    )
                }
                val registry = mockk<EngineRegistry>()
                every { registry.getEngine(SchemaId.Base, EngineKind.CURRENT) } returns engine
                val viaduct = StandardViaduct(registry, standardViaductFactory = mockk(), flagManager = FlagManager.Disabled)
                val results = viaduct.reactiveExecute(ExecutionInput.create("{ value ... @defer { later } }", requestContext = Any()))
                assertEquals(0, calls)

                for (value in 1..2) {
                    val payloads = results.toList()
                    assertEquals(2, payloads.size)
                    val initial = assertInstanceOf(ServiceExecutionResult.Initial::class.java, payloads.first())
                    assertEquals(mapOf("value" to value), initial.getData())
                    assertTrue(initial.hasNext)
                    assertEquals("details", initial.pending.single().label)
                    val update = assertInstanceOf(ServiceExecutionResult.Update::class.java, payloads.last())
                    assertFalse(update.hasNext)
                    val entry = assertInstanceOf(ServiceIncrementalEntry.Object::class.java, update.incremental.single())
                    assertEquals(initial.pending.single().id, entry.id)
                    assertEquals(mapOf("later" to value), entry.data)
                    assertEquals(listOf(entry.id), update.completed.map { it.id })
                }
                coVerify(exactly = 2) { engine.execute(any(), incrementalExecution = true) }
                assertEquals(2, cancellations)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancelling reactiveExecute before the initial result cancels resolution`(engine2Enabled: Boolean): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val viaduct = streamingViaduct(engine2Enabled = engine2Enabled) {
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
        engine2Enabled: Boolean = false,
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
                MockFlagManager(
                    setOfNotNull(
                        FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION,
                        FlagManager.Flags.ENGINE2_ENABLED.takeIf { engine2Enabled },
                    ),
                ),
            )
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
            .build().also {
                assertInstanceOf(if (engine2Enabled) Engine2::class.java else EngineImpl::class.java, it.engineRegistry.getEngine(SchemaId.Base))
            }
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

private const val RUNTIME2_PACKAGE = "viaduct.engine.runtime2"

private fun Engine.graphQL(): GraphQL = (this as viaduct.engine.airbnb.EngineGraphQLJavaCompat).getGraphQL()

private class EngineFlags(
    var engine2Enabled: Boolean = false,
    var perRequest: Boolean = false,
    var useEngine2: Boolean = false,
) : FlagManager {
    override fun isEnabled(flag: FlagManager.Flag): Boolean =
        when (flag) {
            FlagManager.Flags.ENGINE2_ENABLED -> engine2Enabled
            FlagManager.Flags.ENGINE2_PER_REQUEST -> perRequest
            FlagManager.Flags.USE_ENGINE2 -> useEngine2
            else -> false
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
