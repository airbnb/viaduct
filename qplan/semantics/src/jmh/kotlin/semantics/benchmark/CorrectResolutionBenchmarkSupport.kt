package semantics.benchmark

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireQueryTypeDef
import org.openjdk.jmh.infra.Blackhole
import semantics.arbitrary.ResolverBenchmarkCorpus
import semantics.arbitrary.resolverBenchmarkOverheadQueryConfig
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.SharedOperationContext
import semantics.shared.instantiateBindings

internal const val DEFAULT_CORRECT_RESOLUTION_INPUT_COUNT = 50
internal const val DEFAULT_CORRECT_RESOLUTION_LOOP_COUNT = 1
internal const val DEFAULT_CORRECT_RESOLUTION_QUERY_SEED = 1L

/**
 * Prepares completed Resolver26 results outside measurement and benchmarks only the correctness
 * judgment over those immutable results, request worlds, and grounded root selections.
 */
internal class CorrectResolutionBenchmarkSupport(
    private val subject: ResolverBenchmarkSubject,
) {
    private val corpus: ResolverBenchmarkCorpus =
        ResolverBenchmarkCorpus.load(SCHEMA_RESOURCE, REGISTRY_RESOURCE)

    private var preparedInputs: Array<PreparedCorrectResolution> = emptyArray()

    fun prepareTrial(
        inputCount: Int,
        querySeed: Long,
    ) {
        require(inputCount > 0) {
            "Correct-resolution benchmark input count must be positive"
        }
        val testWorld = corpus.world()
        val queries =
            corpus.generateQueries(
                count = inputCount,
                config = resolverBenchmarkOverheadQueryConfig(),
                seed = querySeed,
            )
        check(queries.map { query -> query.source }.distinct().size == inputCount) {
            "Correct-resolution benchmark query corpus must contain $inputCount distinct inputs"
        }
        preparedInputs =
            queries
                .map { query ->
                    val world = testWorld.newAssumptions(selectiveResolvers = true)
                    val operation =
                        SharedOperationContext.create(world, resolverObserver = CorrectnessResolverObserver())
                    val fragment = world.fragmentFrom(query.source)
                    val result =
                        subject.resolve(
                            operation = operation,
                            root = world.objectOf("Query"),
                            selections = fragment.subselections,
                        )
                    val selections: ObjectSelectionForest =
                        fragment.subselections
                            .merge(world.schema.requireQueryTypeDef())
                            .instantiateBindings(operation)
                    check(result.correctResolution(operation, selections)) {
                        "Prepared correct-resolution benchmark input is not a correct resolution"
                    }
                    PreparedCorrectResolution(operation, result, selections)
                }.toTypedArray()
    }

    fun correctResolution(
        loopCount: Int,
        blackhole: Blackhole,
    ): Int {
        require(loopCount > 0) {
            "Correct-resolution benchmark loop count must be positive"
        }
        check(preparedInputs.isNotEmpty()) {
            "Correct-resolution benchmark trial was not prepared"
        }
        repeat(loopCount) {
            preparedInputs.forEach { prepared ->
                blackhole.consume(
                    prepared.result.correctResolution(prepared.operation, prepared.selections),
                )
            }
        }
        return Math.multiplyExact(loopCount, preparedInputs.size)
    }

    private data class PreparedCorrectResolution(
        val operation: SharedOperationContext<*>,
        val result: ObjectEngineResult,
        val selections: ObjectSelectionForest,
    )
}
