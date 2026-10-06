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
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.arbitrary.resolverBenchmarkFullConfig
import viaduct.engine.runtime2.contract.registeredResolverApplicationIdentityCounts
import viaduct.engine.runtime2.contract.validateFromFieldBindings
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings

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

    private var overheadCases: Array<PreparedResolution> = emptyArray()

    fun prepareOverheadInvocation(loopCount: Int) {
        require(loopCount > 0) { "Resolver benchmark loop count must be positive" }
        val testWorld = corpus.world()
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
        val testWorld = corpus.world()
        val variableArgumentCounts = mutableListOf<Long>()
        val samples =
            querySources.map { source ->
                val world = testWorld.newAssumptions(selectiveResolvers = true)
                val selections = testWorld.schemas.fragmentFrom(source).subselections
                corpus.registry.clearResolutionWitness()
                val applicationObservations =
                    Collections.synchronizedList(
                        mutableListOf<ResolverBenchmarkInvocationObservation>(),
                    )
                val witnessObserver = corpus.registry.resolverObserver()
                val observer = object : ResolverObserver {
                    override fun onResolverInvocation(observation: viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation) {
                        witnessObserver.onResolverInvocation(observation)
                        applicationObservations += ResolverBenchmarkInvocationObservation(
                            occurrencePath = observation.occurrencePath,
                            resolverOccurrenceId = observation.resolverOccurrenceId,
                            variableArgumentCount = observation.variableArgumentCount,
                            variableSourceOccurrenceIds = observation.variableResolverOccurrenceIds,
                        )
                    }
                }
                val operation = SharedOperationContext.create(world, resolverObserver = observer)
                val result =
                    subject.resolve(
                        operation = operation,
                        root = world.objectOf("Query"),
                        selections = selections,
                    )
                val witness = corpus.registry.resolutionWitness()
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
                    result.shape(world)
                OverheadSample(
                    fields = shape.fields,
                    activeFields = shape.activeFields,
                    passiveFields = shape.passiveFields,
                    resolverExecutions = witness.applications.size.toLong(),
                    variableBearingResolverExecutions =
                        queryVariableArgumentCounts.count { count -> count > 0 }.toLong(),
                    variableStackDepth = applicationObservations.maximumVariableStackDepth(),
                    depth = shape.depth.toLong(),
                )
            }
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
                appendRegistryStatistics()
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
                ) { testWorld, testCase ->
                    check(testCase.query.selectionDepth >= 4)
                    val world = testWorld.newAssumptions(selectiveResolvers = true)
                    val observer = testCase.registry.resolverObserver()
                    val operation = SharedOperationContext.create(world, resolverObserver = observer)
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

    private data class ResultShape(
        val fields: Long,
        val activeFields: Long,
        val passiveFields: Long,
        val depth: Int,
    )

    private data class OverheadSample(
        val fields: Long,
        val activeFields: Long,
        val passiveFields: Long,
        val resolverExecutions: Long,
        val variableBearingResolverExecutions: Long,
        val variableStackDepth: Long,
        val depth: Long,
    )

    private fun EngineResult?.shape(
        world: Assumptions,
        depth: Int = 0
    ): ResultShape =
        when (this) {
            null, is ErrorEngineResult ->
                ResultShape(
                    fields = 0,
                    activeFields = 0,
                    passiveFields = 0,
                    depth = depth,
                )
            is ListEngineResult ->
                indices
                    .map { index -> get(index).value.get().shape(world, depth) }
                    .fold(
                        ResultShape(
                            fields = 0,
                            activeFields = 0,
                            passiveFields = 0,
                            depth = depth,
                        ),
                    ) { result, child ->
                        result.combine(child)
                    }
            is ObjectEngineResult ->
                keys
                    .map { key ->
                        val child =
                            if (key is ObjectEngineResult.ParentKey) {
                                ResultShape(
                                    fields = 0,
                                    activeFields = 0,
                                    passiveFields = 0,
                                    depth = depth + 1,
                                )
                            } else {
                                getCell(key).value.get().shape(world, depth + 1)
                            }
                        child.copy(
                            fields = child.fields + 1,
                            activeFields =
                                child.activeFields +
                                    if (key.field in world.resolverRegistry) 1 else 0,
                            passiveFields =
                                child.passiveFields +
                                    if (key.field in world.resolverRegistry) 0 else 1,
                        )
                    }.fold(
                        ResultShape(
                            fields = 0,
                            activeFields = 0,
                            passiveFields = 0,
                            depth = depth,
                        ),
                    ) { result, child ->
                        result.combine(child)
                    }
            else ->
                ResultShape(
                    fields = 0,
                    activeFields = 0,
                    passiveFields = 0,
                    depth = depth,
                )
        }

    private fun ResultShape.combine(other: ResultShape): ResultShape =
        ResultShape(
            fields = fields + other.fields,
            activeFields = activeFields + other.activeFields,
            passiveFields = passiveFields + other.passiveFields,
            depth = maxOf(depth, other.depth),
        )

    private fun StringBuilder.appendRegistryStatistics() {
        val activeFieldsPerObject =
            corpus.schema.sourceFieldCoordinatesByObject.values.map { fields ->
                fields.count(corpus.registry.fieldResolverCoordinates::contains).toLong()
            }
        val passiveFieldsPerObject =
            corpus.schema.sourceFieldCoordinatesByObject.values.map { fields ->
                fields.count { field -> field !in corpus.registry.fieldResolverCoordinates }.toLong()
            }
        val objectFragmentSelections =
            corpus.registry.objectFragmentSelectionCounts().map(Int::toLong)
        val objectFragmentDepths =
            corpus.registry.objectFragmentDepths().map(Int::toLong)
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
            "  selections per object fragment: " +
                objectFragmentSelections.statistics(value = { it }),
        )
        append(
            "  object fragment depth: " +
                objectFragmentDepths.statistics(value = { it }),
        )
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
