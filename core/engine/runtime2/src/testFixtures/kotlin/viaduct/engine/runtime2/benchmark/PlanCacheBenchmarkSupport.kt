@file:Suppress("DEPRECATION", "ForbiddenImport")

package viaduct.engine.runtime2.benchmark

import graphql.ExecutionInput as GraphQLExecutionInput
import graphql.ParseAndValidate
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.AstPrinter
import graphql.language.AstTransformer
import graphql.language.Document
import graphql.language.Field
import graphql.language.Node
import graphql.language.NodeVisitorStub
import graphql.language.SourceLocation
import graphql.schema.GraphQLSchema
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import graphql.validation.ValidationErrorType
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ListValueSize
import viaduct.engine.runtime2.arbitrary.MaxSelectionDepth
import viaduct.engine.runtime2.arbitrary.MinimumSelectionDepth
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.PassiveAbstractOutputTypeWeight
import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.RandomParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromObjectFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromProviderVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.RootFieldReferencesEnabled
import viaduct.engine.runtime2.arbitrary.RootQueryFieldCount
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight
import viaduct.engine.runtime2.arbitrary.encodeResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.registry
import viaduct.engine.runtime2.arbitrary.resolverBenchmarkCorpusSearchConfig
import viaduct.engine.runtime2.arbitrary.schema
import viaduct.service.api.ExecutionInput
import viaduct.service.api.ExecutionResult
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.DocumentProviderFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

/**
 * The first plan-cache step's workload profile: generated field and node resolvers with object and
 * Query fragments, lists, interfaces and unions. It has no `@skip`/`@include`, `@parent`, nested
 * operations, root-field references, sometimes-passive fields, access checkers, or resolver variables.
 */
fun planCacheStepOneConfig(): Config =
    resolverBenchmarkCorpusSearchConfig() +
        (ResolverVariablesEnabled to false) +
        (ResolverFromArgumentVariablesEnabled to false) +
        (ResolverFromObjectFieldVariablesEnabled to false) +
        (ResolverFromProviderVariablesEnabled to false) +
        (ResolverFromQueryFieldVariablesEnabled to false) +
        (ResolverVariableSingletonCoercionEnabled to false) +
        (ParentFieldsEnabled to false) +
        (RandomParentFieldsEnabled to false) +
        (RootFieldReferencesEnabled to false) +
        (SometimesPassiveFieldWeight to 0.0) +
        (PassiveAbstractOutputTypeWeight to 0.3)

private fun planCacheStepOneQueryConfig(): Config =
    planCacheStepOneConfig() +
        (MinimumSelectionDepth to 8) +
        (MaxSelectionDepth to 10) +
        (RootQueryFieldCount to 6..8)

/** How requests reach the operation plan cache. */
enum class PlanCacheDocuments {
    /** Every execution of one operation receives the same cached document, as with the service document cache. */
    SHARED,

    /** Every execution receives a document instance never seen before, so only within-request reuse applies. */
    FRESH,
}

/** One generated plan-cache step-1 schema, executable registry recipe, and validated operations. */
class PlanCacheWorkload private constructor(
    val corpus: ResolverBenchmarkCorpus,
    val operations: List<String>,
) {
    companion object {
        fun generate(
            seed: Long,
            queryCount: Int,
            listSize: Int = 2,
        ): PlanCacheWorkload {
            val config = planCacheStepOneConfig() + (ListValueSize to listSize..listSize)
            val random = RandomSource(Random(seed), seed)
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            val corpus = ResolverBenchmarkCorpus.decode(schema.sdl, registry.encodeResolverBenchmarkCorpus(schema))
            val operations = corpus.generateQueries(queryCount, planCacheStepOneQueryConfig() + (ListValueSize to listSize..listSize), seed).map { benchmarkOperation(it.source) }
            return PlanCacheWorkload(corpus, operations)
        }
    }
}

/**
 * Executes a [PlanCacheWorkload] through `StandardViaduct` on Engine2 with the plan cache on or off.
 * Schema assembly, output preparation, alias repair, parsing and validation, one preparation
 * execution per operation, and request construction are untimed.
 */
class PlanCacheBenchmarkSupport(
    workload: PlanCacheWorkload,
    private val planCache: Boolean,
    private val documents: PlanCacheDocuments,
) {
    private val documentEntries = ConcurrentHashMap<String, PreparsedDocumentEntry>()
    private val graphQLSchema: GraphQLSchema
    private val viaduct: StandardViaduct
    val operations: List<String>

    /** The preparation responses, in operation order. */
    val responses: List<Map<String, Any?>>
    private var requests: List<ExecutionInput> = emptyList()
    private var invocation = 0

    init {
        val discovery = PrecomputedResolverOutputs()
        val world = workload.corpus.registry.world(schemaSDL = workload.corpus.schemaSDL, precomputedResolverOutputs = discovery)
        val adapter = BenchmarkExecutorAdapter(world, workload.corpus.registry)
        val module = adapter.module()
        graphQLSchema = module.fullSchema.schema
        operations = workload.operations.mapNotNull { operation -> repairValidation(graphQLSchema, operation) }
        viaduct = StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withCheckerExecutorFactory(MockCheckerExecutorFactory(module.checkerExecutors, module.typeCheckerExecutors))
            .withSchemaConfiguration(SchemaConfiguration.fromSchema(module.fullSchema))
            .withDocumentProviderFactory(PreparedDocuments(documentEntries))
            .withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED || (planCache && flag == FlagManager.Flags.ENGINE2_PLAN_CACHE)
            })
            .build()
        operations.forEach(::prepareDocument)
        responses = operations.map { operation -> execute(ExecutionInput.create(operationText = operation)).toSpecification() }
        adapter.freeze()
    }

    fun prepareInvocation(loopCount: Int) {
        require(loopCount > 0) { "Benchmark loop count must be positive" }
        invocation++
        requests = List(loopCount * operations.size) { index ->
            val operation = operations[index % operations.size]
            val text = when (documents) {
                PlanCacheDocuments.SHARED -> operation
                PlanCacheDocuments.FRESH -> "$operation\n# invocation $invocation request $index".also(::prepareDocument)
            }
            ExecutionInput.create(operationText = text)
        }
    }

    fun executeBatch(consume: (ExecutionResult) -> Unit): Int {
        check(requests.isNotEmpty()) { "Benchmark invocation was not prepared" }
        runBlocking {
            requests.forEach { request -> consume(viaduct.execute(request, SchemaId.Base)) }
        }
        if (documents == PlanCacheDocuments.FRESH) requests.forEach { documentEntries.remove(it.operationText) }
        return requests.size
    }

    private fun execute(request: ExecutionInput): ExecutionResult = runBlocking { viaduct.execute(request, SchemaId.Base) }

    /** Parses and validates outside measurement; the service then receives the prepared document instance. */
    private fun prepareDocument(text: String) {
        documentEntries.computeIfAbsent(text) {
            val result = ParseAndValidate.parseAndValidate(graphQLSchema, GraphQLExecutionInput.newExecutionInput(text).build())
            check(!result.isFailure) { "Invalid plan-cache benchmark operation: ${result.errors}" }
            PreparsedDocumentEntry(result.document)
        }
    }
}

/** Response sizes for reporting: field count including list expansion, and object count. */
fun planCacheResponseSize(response: Map<String, Any?>): Pair<Int, Int> {
    var fields = 0
    var objects = 0

    fun visit(value: Any?) {
        when (value) {
            is Map<*, *> -> {
                objects++
                fields += value.size
                value.values.forEach(::visit)
            }
            is List<*> -> value.forEach(::visit)
        }
    }
    visit(response["data"])
    return fields to objects
}

private class PreparedDocuments(
    private val entries: Map<String, PreparsedDocumentEntry>,
) : DocumentProviderFactory {
    override fun create(
        schemaId: SchemaId,
        schema: EngineSchema,
    ): PreparsedDocumentProvider =
        PreparsedDocumentProvider { input, _ ->
            CompletableFuture.completedFuture(checkNotNull(entries[input.query]) { "Unprepared plan-cache benchmark operation" })
        }
}

/**
 * Generated selections reuse field names across object types, so abstract-typed selections can
 * violate GraphQL's overlapping-field rule. Aliasing one field of each reported conflict preserves
 * the selected work while making the operation valid. Operations with other errors are dropped.
 */
private fun repairValidation(
    schema: GraphQLSchema,
    operation: String,
): String? {
    var text = operation
    var aliases = 0
    repeat(MAX_REPAIR_ROUNDS) {
        val result = ParseAndValidate.parseAndValidate(schema, GraphQLExecutionInput.newExecutionInput(text).build())
        if (!result.isFailure) return text
        val document = result.document ?: return null
        val conflicts = result.validationErrors.filter { it.validationErrorType == ValidationErrorType.FieldsConflict }
        if (conflicts.isEmpty() || conflicts.size != result.validationErrors.size) return null
        val targets = conflicts.mapNotNull { it.locations.lastOrNull() }.toSet()
        text = AstPrinter.printAst(document.aliasFieldsAt(targets) { "pc${aliases++}_${it.resultKey}" })
    }
    return null
}

private fun Document.aliasFieldsAt(
    locations: Set<SourceLocation>,
    alias: (Field) -> String,
): Document {
    val wanted = locations.map { it.line to it.column }.toSet()
    return AstTransformer().transform(
        this,
        object : NodeVisitorStub() {
            override fun visitField(
                node: Field,
                context: TraverserContext<Node<*>>,
            ): TraversalControl {
                val location = node.sourceLocation
                if (location != null && (location.line to location.column) in wanted) {
                    TreeTransformerUtil.changeNode(context, node.transform { it.alias(alias(node)) })
                }
                return TraversalControl.CONTINUE
            }
        },
    ) as Document
}

private const val MAX_REPAIR_ROUNDS = 64

/** Prints plan-cache step-1 workload sizes for the seeds given as arguments; used to choose the benchmark seed. */
object PlanCacheWorkloadReport {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val queryCount = System.getProperty("planCacheWorkloadQueries")?.toInt() ?: 100
        val listSize = System.getProperty("planCacheWorkloadListSize")?.toInt() ?: 2
        arguments.map(String::toLong).forEach { seed ->
            val started = System.nanoTime()
            val support =
                try {
                    PlanCacheBenchmarkSupport(PlanCacheWorkload.generate(seed, queryCount, listSize), planCache = false, documents = PlanCacheDocuments.SHARED)
                } catch (failure: IllegalStateException) {
                    println("seed=$seed unsupported by the StandardViaduct adapter: ${failure.message?.take(120)}")
                    return@forEach
                }
            val sizes = support.responses.map(::planCacheResponseSize)
            val errors = support.responses.count { (it["errors"] as? List<*>)?.isNotEmpty() == true }
            println(
                "seed=$seed listSize=$listSize operations=${support.operations.size}/$queryCount " +
                    "fields avg=${"%.1f".format(sizes.map { it.first }.average())} p50=${sizes.map { it.first }.sorted()[sizes.size / 2]} max=${sizes.maxOf { it.first }} " +
                    "objects avg=${"%.1f".format(sizes.map { it.second }.average())} errorResponses=$errors " +
                    "setup=${(System.nanoTime() - started) / 1_000_000}ms",
            )
        }
    }
}
