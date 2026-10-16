@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.benchmark

import graphql.ExecutionResult as GraphQLExecutionResult
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.AstPrinter
import graphql.language.FragmentDefinition
import graphql.parser.Parser
import java.util.Locale
import java.util.concurrent.CompletableFuture
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.testing.NodeResolvers
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.graphql.schema.ViaductSchema
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.DocumentProviderFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

internal const val BENCHMARK_RESOURCE_PREFIX = "viaduct/engine/runtime2/benchmark/engine-comparison/"

internal enum class BenchmarkEngine {
    OLD,
    NEW,
}

internal enum class BenchmarkNodeCache(val enabled: Boolean) {
    ENABLED(true),
    DISABLED(false),
    ;

    companion object {
        fun parse(value: String): BenchmarkNodeCache = valueOf(value.replace('-', '_').uppercase(Locale.ROOT))
    }
}

internal enum class BenchmarkCycleChecker {
    DEFERRED,
    NOP,
    ;

    fun create(): CycleCheckState =
        when (this) {
            DEFERRED -> CycleCheckState.createDeferred()
            NOP -> CycleCheckState.createNOP()
        }

    companion object {
        fun parse(value: String): BenchmarkCycleChecker = valueOf(value.uppercase(Locale.ROOT))
    }
}

internal class StandardViaductBenchmarkSupport(
    engine: BenchmarkEngine,
    loader: ClassLoader = StandardViaductBenchmarkSupport::class.java.classLoader,
    callbackCounts: BenchmarkCallbackCounts? = null,
    nodeCache: BenchmarkNodeCache = BenchmarkNodeCache.ENABLED,
    cycleChecker: BenchmarkCycleChecker = BenchmarkCycleChecker.DEFERRED,
) {
    private val corpus = ResolverBenchmarkCorpus.load(BENCHMARK_RESOURCE_PREFIX + "schema.graphqls", BENCHMARK_RESOURCE_PREFIX + "registry.json.gz", loader)
    val querySources = ResolverBenchmarkQueryCorpus.load(BENCHMARK_RESOURCE_PREFIX + "queries.json", loader).querySources
    private val checkers = ChinaActiveBenchmarkCheckers(corpus, comparisonMode = true)
    val operations = querySources.map(::benchmarkOperation)
    private val documents = BenchmarkDocuments()
    private val viaduct: StandardViaduct
    private var requests: List<ExecutionInput> = emptyList()

    init {
        require(engine == BenchmarkEngine.NEW || cycleChecker == BenchmarkCycleChecker.DEFERRED) {
            "The cycle-checker control only applies to the new engine"
        }
        val discovery = PrecomputedResolverOutputs()
        val preparedWorld = world(discovery)
        val adapter = BenchmarkExecutorAdapter(preparedWorld, corpus.registry)
        val module = adapter.module().let { module -> callbackCounts?.let { module.countCallbacks(it) } ?: module }
        viaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withCheckerExecutorFactory(MockCheckerExecutorFactory(module.checkerExecutors, module.typeCheckerExecutors))
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(module.fullSchema))
            .withDocumentProviderFactory(documents)
            .withNodeResolverCaching(nodeCache.enabled)
            .withInstrumentation(
                if (engine == BenchmarkEngine.NEW) {
                    object : SimplePerformantInstrumentation() {
                        override fun beginExecution(
                            parameters: InstrumentationExecutionParameters,
                            state: InstrumentationState?,
                        ): InstrumentationContext<GraphQLExecutionResult>? {
                            parameters.graphQLContext.put(CycleCheckState::class.java, cycleChecker.create())
                            return null
                        }
                    }
                } else {
                    null
                },
            )
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = engine == BenchmarkEngine.NEW && flag == FlagManager.Flags.ENGINE2_ENABLED
            })
            .build()
        val responses = operations.map { operation -> execute(ExecutionInput.create(operationText = operation)).toSpecification() }
        validateBenchmarkResponses("$engine nodeCache=$nodeCache cycleChecker=$cycleChecker preparation", responses)
        adapter.freeze()
        documents.freeze()
        println(
            "StandardViaduct $engine uses Dispatchers.Default: availableProcessors=${Runtime.getRuntime().availableProcessors()}, " +
                "scheduler.core.pool.size=${System.getProperty("kotlinx.coroutines.scheduler.core.pool.size", "default")}, " +
                "scheduler.max.pool.size=${System.getProperty("kotlinx.coroutines.scheduler.max.pool.size", "default")}",
        )
    }

    fun prepareInvocation(loopCount: Int) {
        require(loopCount > 0) { "Benchmark loop count must be positive" }
        requests = List(loopCount * querySources.size) { index ->
            ExecutionInput.create(operationText = operations[index % operations.size])
        }
    }

    fun executeBatch(consume: (ExecutionResult) -> Unit): Int {
        check(requests.isNotEmpty()) { "Benchmark invocation was not prepared" }
        runBlocking {
            requests.forEach { request -> consume(viaduct.execute(request, SchemaId.Base)) }
        }
        return requests.size
    }

    fun executeQuery(index: Int): ExecutionResult = execute(ExecutionInput.create(operationText = operations[index]))

    private fun execute(request: ExecutionInput): ExecutionResult = runBlocking { viaduct.execute(request, SchemaId.Base) }

    private fun world(outputs: PrecomputedResolverOutputs): TestWorld =
        corpus.registry.world(
            schemaSDL = checkers.schemaSDL,
            precomputedResolverOutputs = outputs,
            additionalFieldResolvers = checkers::fieldResolvers,
            fieldCheckerFactory = checkers::fieldCheckers,
            typeCheckerFactory = checkers::typeCheckers,
        )
}

internal class BenchmarkNodeResolvers
    @Inject
    constructor(
        @NodeResolvers val resolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
    )

private class BenchmarkDocuments : DocumentProviderFactory {
    private val entries = mutableMapOf<String, PreparsedDocumentEntry>()
    private var frozen = false

    fun freeze() {
        frozen = true
    }

    override fun create(
        schemaId: SchemaId,
        schema: EngineSchema
    ): PreparsedDocumentProvider =
        PreparsedDocumentProvider { input, parseAndValidate ->
            val entry = entries[input.query] ?: run {
                check(!frozen) { "Unprepared benchmark query" }
                parseAndValidate.apply(input).also {
                    check(!it.hasErrors()) { "Invalid comparison query: ${it.errors}" }
                    entries[input.query] = it
                }
            }
            CompletableFuture.completedFuture(entry)
        }
}

internal fun benchmarkOperation(source: String): String {
    val fragment = Parser().parseDocument(source).definitions.single() as FragmentDefinition
    return "query Benchmark { ...${fragment.name} }\n${AstPrinter.printAst(fragment)}"
}

internal fun validateBenchmarkResponses(
    label: String,
    responses: List<Map<String, Any?>>,
    expected: List<Map<String, Any?>>? = null,
    strict: Boolean = java.lang.Boolean.getBoolean("standardViaductStrictValidation"),
): List<Int> {
    require(expected == null || expected.size == responses.size) { "Response batches must have the same size" }
    val errors = responses.indices.filter { (responses[it]["errors"] as? List<*>)?.isNotEmpty() == true }
    val differences = if (expected == null) emptyList() else responses.indices.filter { responses[it] != expected[it] }
    println("StandardViaduct $label: ${responses.size} responses; GraphQL error indices: $errors; differing query indices: $differences")
    errors.firstOrNull()?.let { println("First benchmark errors: ${(responses[it]["errors"] as List<*>).take(3)}") }
    check(!strict || (errors.isEmpty() && differences.isEmpty())) { "$label failed strict response validation: errors=$errors, differences=$differences" }
    return differences
}
