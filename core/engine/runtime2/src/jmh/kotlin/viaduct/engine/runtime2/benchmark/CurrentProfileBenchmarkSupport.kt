@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.infra.Blackhole
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.arbitrary.resolverBenchmarkFullConfig
import viaduct.engine.runtime2.contract.registeredResolverApplicationIdentityCounts
import viaduct.engine.runtime2.contract.validateFromFieldBindings
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.CheckerObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD
import viaduct.graphql.schema.ViaductSchema

internal const val DEFAULT_OVERHEAD_LOOP_COUNT = 1

internal const val SCHEMA_RESOURCE =
    "viaduct/engine/runtime2/benchmark/current-profile/schema.graphqls"
internal const val REGISTRY_RESOURCE =
    "viaduct/engine/runtime2/benchmark/current-profile/registry.json.gz"
internal const val QUERIES_RESOURCE =
    "viaduct/engine/runtime2/benchmark/current-profile/queries.json"
private const val REPORT_FILE_PROPERTY = "resolverBenchmarkReportFile"

internal fun interface ResolverBenchmarkSubject {
    fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult
}

internal data class ResolverBenchmarkInvocationObservation(
    val occurrencePath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val variableArgumentCount: Int,
    val variableSourceOccurrenceIds: Set<ResolverOccurrenceId>,
)

internal class CurrentProfileBenchmarkSupport(
    private val subject: ResolverBenchmarkSubject,
) {
    private val corpus: ResolverBenchmarkCorpus =
        ResolverBenchmarkCorpus.load(SCHEMA_RESOURCE, REGISTRY_RESOURCE)
    private val querySources: Array<String> =
        ResolverBenchmarkQueryCorpus
            .load(QUERIES_RESOURCE)
            .querySources
            .toTypedArray()

    private val fixture = ResolverBenchmarkFixture(corpus)
    private val overheadCheckers = fixture.checkers

    private var overheadCases: Array<PreparedResolution> = emptyArray()

    private val precomputedResolverOutputs: PrecomputedResolverOutputs by lazy {
        val discovery = PrecomputedResolverOutputs()
        val testWorld = corpus.benchmarkWorld(discovery)
        querySources.forEach { source ->
            val world = testWorld.newAssumptions(selectiveResolvers = true)
            subject.resolve(
                operation = SharedOperationContext.create(world),
                root = world.objectOf("Query"),
                selections = testWorld.schemas.fragmentFrom(source).subselections,
            )
        }
        discovery.prepared()
    }

    fun prepareOverheadInvocation(loopCount: Int) {
        require(loopCount > 0) { "Resolver benchmark loop count must be positive" }
        val testWorld = corpus.benchmarkWorld()
        val parsedQueries =
            querySources.map { source ->
                testWorld.schemas.fragmentFrom(source).subselections
            }
        overheadCases =
            Array(loopCount * parsedQueries.size) { index ->
                val selections = parsedQueries[index % parsedQueries.size]
                val world = testWorld.newAssumptions(selectiveResolvers = true)
                PreparedResolution(
                    operation = SharedOperationContext.create(world),
                    root = world.objectOf("Query"),
                    selections = selections,
                )
            }
    }

    fun overhead(blackhole: Blackhole): Int {
        check(overheadCases.isNotEmpty()) {
            "Overhead invocation was not prepared"
        }
        overheadCases.forEach { prepared ->
            blackhole.consume(
                subject.resolve(
                    operation = prepared.operation,
                    root = prepared.root,
                    selections = prepared.selections,
                ),
            )
        }
        return overheadCases.size
    }

    fun reportOverheadStatistics() {
        val testWorld = corpus.benchmarkWorld()
        val coverage = ResolverBenchmarkCoverage()
        val variableArgumentCounts = mutableListOf<Long>()
        val samples =
            querySources.mapIndexed { queryIndex, source ->
                val world = testWorld.newAssumptions(selectiveResolvers = true)
                val selections = testWorld.schemas.fragmentFrom(source).subselections
                corpus.registry.clearResolutionWitness()
                val applicationObservations =
                    Collections.synchronizedList(
                        mutableListOf<ResolverBenchmarkInvocationObservation>(),
                    )
                val witnessObserver = corpus.registry.resolverObserver()
                val checkerKinds =
                    Collections.synchronizedList(
                        mutableListOf<CheckerKind>(),
                    )
                val observer = object : CorrectnessResolverObserver(witnessObserver) {
                    override fun onResolverInvocation(observation: viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation) {
                        super.onResolverInvocation(observation)
                        applicationObservations += ResolverBenchmarkInvocationObservation(
                            occurrencePath = observation.occurrencePath,
                            resolverOccurrenceId = observation.resolverOccurrenceId,
                            variableArgumentCount = observation.variableArgumentCount,
                            variableSourceOccurrenceIds = observation.variableResolverOccurrenceIds,
                        )
                    }
                }
                val fieldCheckerInputCounts = Collections.synchronizedList(mutableListOf<Long>())
                val checkerInputCounts = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
                val checkerApplications = Collections.synchronizedList(mutableListOf<CheckerInvocationObservation>())
                val operation =
                    SharedOperationContext.create(
                        world,
                        resolverObserver = observer,
                        checkerObserver =
                            CorrectnessCheckerObserver(object : CheckerObserver {
                                override fun onCheckerInvocation(observation: CheckerInvocationObservation) {
                                    checkerKinds += observation.checkerKind
                                    checkerApplications += observation
                                }

                                override fun onCheckerInvocation(
                                    observation: CheckerInvocationObservation,
                                    inputs: Map<String, viaduct.engine.runtime2.model.registry.CheckerInput>,
                                ) {
                                    onCheckerInvocation(observation)
                                    if (observation.checkerKind == CheckerKind.FIELD) fieldCheckerInputCounts += inputs.size.toLong()
                                    checkerInputCounts += inputs.size.toLong() to inputs.values.sumOf { input ->
                                        (if (input.objectValue.getSelections().any()) 1L else 0L) +
                                            (if (input.queryValue.getSelections().any()) 1L else 0L)
                                    }
                                }
                            }),
                    )
                val result =
                    subject.resolve(
                        operation = operation,
                        root = world.objectOf("Query"),
                        selections = selections,
                    )
                val witness = corpus.registry.resolutionWitness()
                coverage.record(queryIndex, operation, result, checkerApplications)
                check(applicationObservations.size == witness.applications.size) {
                    "Observed ${applicationObservations.size} variable-argument counts " +
                        "for ${witness.applications.size} resolver executions"
                }
                val queryVariableArgumentCounts =
                    applicationObservations.map { observation ->
                        observation.variableArgumentCount.toLong()
                    }
                variableArgumentCounts += queryVariableArgumentCounts.filter { count -> count > 0 }
                val shape =
                    runBlocking { result.benchmarkShape(world) }
                OverheadSample(
                    fields = shape.fields,
                    activeFields = shape.activeFields,
                    passiveFields = shape.passiveFields,
                    resolverExecutions = witness.applications.size.toLong(),
                    fieldCheckerExecutions = checkerKinds.count { kind -> kind == CheckerKind.FIELD }.toLong(),
                    typeCheckerExecutions = checkerKinds.count { kind -> kind == CheckerKind.TYPE }.toLong(),
                    namedCheckerInputs = checkerInputCounts.sumOf { it.first },
                    namedFieldCheckerInputs = fieldCheckerInputCounts.sum(),
                    populatedCheckerRoots = checkerInputCounts.sumOf { it.second },
                    variableBearingResolverExecutions =
                        queryVariableArgumentCounts.count { count -> count > 0 }.toLong(),
                    variableStackDepth = applicationObservations.maximumVariableStackDepth(),
                    depth = shape.depth.toLong(),
                )
            }
        coverage.assertComplete()
        val report =
            buildString {
                appendLine(
                    "Resolver overhead corpus statistics " +
                        "(${querySources.size} queries):",
                )
                appendLine(
                    "  fields returned: " +
                        samples.statistics(OverheadSample::fields),
                )
                appendLine(
                    "  active fields returned: " +
                        samples.statistics(OverheadSample::activeFields),
                )
                appendLine(
                    "  passive fields returned: " +
                        samples.statistics(OverheadSample::passiveFields),
                )
                appendLine(
                    "  passive fields per active field: " +
                        samples.ratioStatistics(
                            numerator = OverheadSample::passiveFields,
                            denominator = OverheadSample::activeFields,
                        ),
                )
                appendLine(
                    "  resolvers executed: " +
                        samples.statistics(OverheadSample::resolverExecutions),
                )
                appendLine(
                    "  field checkers executed: " +
                        samples.statistics(OverheadSample::fieldCheckerExecutions),
                )
                appendLine(
                    "  type checkers executed: " +
                        samples.statistics(OverheadSample::typeCheckerExecutions),
                )
                appendLine(
                    "  resolver executions with variable-bearing arguments: " +
                        samples.statistics(
                            value = OverheadSample::variableBearingResolverExecutions,
                            percentile = 0.5,
                            percentileName = "p50",
                        ),
                )
                appendLine(
                    "  variable-bearing arguments per such resolver execution: " +
                        variableArgumentCounts.statistics(
                            value = { count -> count },
                        ),
                )
                appendLine(
                    "  maximum variable stack depth: " +
                        samples.statistics(
                            value = OverheadSample::variableStackDepth,
                            percentile = 0.5,
                            percentileName = "p50",
                        ),
                )
                appendLine(
                    "  result depth: " +
                        samples.statistics(OverheadSample::depth),
                )
                appendLine("  named checker inputs: " + samples.statistics(OverheadSample::namedCheckerInputs))
                appendLine("  named field checker inputs: " + samples.statistics(OverheadSample::namedFieldCheckerInputs))
                appendLine("  populated checker input roots: " + samples.statistics(OverheadSample::populatedCheckerRoots))
                appendRegistryStatistics(testWorld)
                append(coverage.report())
            }
        val reportFile = System.getProperty(REPORT_FILE_PROPERTY)
        if (reportFile == null) {
            println()
            println(report)
        } else {
            val path = Path.of(reportFile)
            path.parent?.let { parent -> Files.createDirectories(parent) }
            Files.writeString(path, report + System.lineSeparator())
        }
    }

    fun full(): Int =
        runBlocking {
            var verifiedCases = 0
            val run =
                checkResolverTestCases(
                    counts = FULL_COUNTS,
                    config = resolverBenchmarkFullConfig(),
                    profile = "resolver-benchmark-full",
                    seed = 1L,
                    fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
                    typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_SUCCESS,
                ) { testWorld, testCase ->
                    check(testCase.query.selectionDepth >= 4)
                    val world = testWorld.newAssumptions(selectiveResolvers = true)
                    val observer = testCase.registry.resolverObserver()
                    val operation =
                        SharedOperationContext.create(
                            world,
                            resolverObserver = observer,
                            checkerObserver = CorrectnessCheckerObserver(),
                        )
                    val fragment = testWorld.schemas.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val result =
                        subject.resolve(
                            operation = operation,
                            root = world.objectOf("Query"),
                            selections = fragment.subselections,
                        )
                    val witness = testCase.registry.resolutionWitness()
                    check(
                        result.registeredResolverApplicationIdentityCounts(operation) == witness.applicationIdentityCounts(),
                    )
                    check(
                        result.correctResolution(
                            operation,
                            fragment.subselections
                                .merge(world.schema.requireQueryTypeDef())
                                .instantiateBindings(operation),
                        ),
                    )
                    result.validateFromFieldBindings(operation, observer.invokedResolverOccurrences())
                    verifiedCases += 1
                }
            check(run.attemptedCases == FULL_CASE_COUNT)
            check(verifiedCases == FULL_CASE_COUNT)
            verifiedCases
        }

    private data class PreparedResolution(
        val operation: SharedOperationContext<*>,
        val root: EngineObjectData.Sync,
        val selections: SelectionForest,
    )

    private data class OverheadSample(
        val fields: Long,
        val activeFields: Long,
        val passiveFields: Long,
        val resolverExecutions: Long,
        val fieldCheckerExecutions: Long,
        val typeCheckerExecutions: Long,
        val namedCheckerInputs: Long,
        val namedFieldCheckerInputs: Long,
        val populatedCheckerRoots: Long,
        val variableBearingResolverExecutions: Long,
        val variableStackDepth: Long,
        val depth: Long,
    )

    private fun ResolverBenchmarkCorpus.benchmarkWorld(outputs: PrecomputedResolverOutputs = precomputedResolverOutputs): TestWorld = fixture.world(outputs)

    private fun StringBuilder.appendRegistryStatistics(checkerWorld: TestWorld) {
        val registry = checkerWorld.resolverRegistry
        val objects = checkerWorld.schema.types.values.filterIsInstance<ViaductSchema.Object>()
        val ordinaryObjects = objects.filter { it.name.matches(Regex("Object\\d+")) || it.name.startsWith("Coverage") }
        val fields = objects.flatMap { it.fields }
        val resolvers = fields.filter { it in registry }.map(registry::resolver)
        val activeFieldsPerObject = ordinaryObjects.map { type -> type.fields.count { it.name != LOWERED_TYPENAME_FIELD && !it.isParentField() && it in registry }.toLong() }
        val passiveFieldsPerObject = ordinaryObjects.map {
                type ->
            type.fields.count { (it.isParentField() || it !in registry) && !it.name.startsWith("__") && it.name != LOWERED_TYPENAME_FIELD }.toLong()
        }

        fun SelectionForest.selectionCount(): Int {
            var count = 0
            forEach { count += 1 + it.subselections.selectionCount() }
            return count
        }

        fun SelectionForest.selectionDepth(): Int {
            var depth = 0
            forEach { depth = maxOf(depth, 1 + it.subselections.selectionDepth()) }
            return depth
        }
        val objectFragmentSelections = resolvers.map { it.objectFragment.selectionCount().toLong() }
        val objectFragmentDepths = resolvers.map { it.objectFragment.selectionDepth().toLong() }
        appendLine("Resolver benchmark registry statistics:")
        appendLine(
            "  active fields per non-Query object: " +
                activeFieldsPerObject.statistics(value = { it }),
        )
        appendLine(
            "  passive fields per non-Query object: " +
                passiveFieldsPerObject.statistics(value = { it }),
        )
        appendLine(
            "  combined selections per object fragment: " +
                objectFragmentSelections.statistics(value = { it }),
        )
        appendLine(
            "  combined object fragment depth: " +
                objectFragmentDepths.statistics(value = { it }),
        )
        appendLine("  bulk selections per object fragment: ${corpus.registry.objectFragmentSelectionCounts().map(Int::toLong).statistics(value = { it })}")
        appendLine("  bulk object fragment depth: ${corpus.registry.objectFragmentDepths().map(Int::toLong).statistics(value = { it })}")
        appendLine("Resolver benchmark feature counts:")
        appendLine("  ordinary object types: ${ordinaryObjects.size}")
        appendLine("  total object types including policy helpers: ${objects.size}")
        appendLine("  installed field resolvers: ${resolvers.size}")
        appendLine("  checker profile: China active plus bounded prepared success/denial feature cases")
        val fieldCheckers = fields.mapNotNull(registry::fieldChecker)
        val typeCheckers = objects.mapNotNull(registry::typeChecker)
        appendLine("  field checkers: ${fieldCheckers.size}")
        appendLine("  type checkers: ${typeCheckers.size}")
        appendLine("  named inputs per field checker: ${fieldCheckers.map { it.fragmentTemplates.size.toLong() }.statistics(value = { it })}")
        appendLine("  named inputs per type checker: ${typeCheckers.map { it.fragmentTemplates.size.toLong() }.statistics(value = { it })}")
        appendLine("  bulk field checker policies: ${overheadCheckers.fieldPolicies.values.flatten().groupingBy { if (it.startsWith("privacy")) "privacy" else it }.eachCount()}")
        appendLine("  checker-only prepared Query resolvers: ${overheadCheckers.typePolicies.size + 1}")
        appendLine("  Query fragments: ${resolvers.count { !it.queryFragment.isEmpty() }}")
        val variables = resolvers.flatMap { it.variables.values }
        appendLine("  FromArgument variables: ${variables.count { it is VariableDefinition.FromArgument }}")
        appendLine("  FromObjectField variables: ${variables.count { it is VariableDefinition.FromField && it.providerFragment == ProviderFragment.OBJECT }}")
        appendLine("  FromQueryField variables: ${variables.count { it is VariableDefinition.FromField && it.providerFragment == ProviderFragment.QUERY }}")
        appendLine("  FromProvider variables: ${variables.count { it == VariableDefinition.FromProvider }}")
    }

    private fun <T> List<T>.statistics(
        value: (T) -> Long,
        percentile: Double = 0.9,
        percentileName: String = "p90",
    ): String {
        val values = map(value).sorted()
        val average = values.average()
        val percentileIndex =
            ceil(values.size * percentile).toInt().coerceAtLeast(1) - 1
        return "average=%.2f, $percentileName=%d, max=%d".format(
            Locale.ROOT,
            average,
            values[percentileIndex],
            values.last(),
        )
    }

    private fun <T> List<T>.ratioStatistics(
        numerator: (T) -> Long,
        denominator: (T) -> Long,
    ): String {
        val values =
            map { sample ->
                numerator(sample).toDouble() / denominator(sample).coerceAtLeast(1)
            }.sorted()
        val percentileIndex = ceil(values.size * 0.9).toInt().coerceAtLeast(1) - 1
        return "average=%.2f, p90=%.2f, max=%.2f".format(
            Locale.ROOT,
            values.average(),
            values[percentileIndex],
            values.last(),
        )
    }

    private fun List<ResolverBenchmarkInvocationObservation>.maximumVariableStackDepth(): Long {
        val executedOccurrences =
            mapTo(linkedSetOf()) { observation -> observation.resolverOccurrenceId }
        val childrenBySource =
            buildMap<ResolverOccurrenceId, MutableSet<ResolverOccurrenceId>> {
                this@maximumVariableStackDepth.forEach { observation ->
                    observation.variableSourceOccurrenceIds
                        .filter(executedOccurrences::contains)
                        .forEach { sourceId ->
                            getOrPut(sourceId, ::linkedSetOf)
                                .add(observation.resolverOccurrenceId)
                        }
                }
            }
        val depthByOccurrence = mutableMapOf<ResolverOccurrenceId, Long>()
        val visiting = mutableSetOf<ResolverOccurrenceId>()

        fun depth(identity: ResolverOccurrenceId): Long {
            depthByOccurrence[identity]?.let { return it }
            check(visiting.add(identity)) {
                "Variable resolver dependency cycle at $identity"
            }
            val depth =
                childrenBySource[identity]
                    .orEmpty()
                    .maxOfOrNull { child -> 1L + depth(child) }
                    ?: 0
            visiting.remove(identity)
            depthByOccurrence[identity] = depth
            return depth
        }
        return executedOccurrences.maxOfOrNull(::depth) ?: 0
    }

    private companion object {
        const val FULL_CASE_COUNT = 1_000

        val FULL_COUNTS =
            TestCaseCount(
                schemas = 100,
                registriesPerSchema = 2,
                queriesPerSchema = 5,
            )
    }
}
