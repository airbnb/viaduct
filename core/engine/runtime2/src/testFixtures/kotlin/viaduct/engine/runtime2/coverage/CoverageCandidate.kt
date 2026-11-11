package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.generateResolverTestBatches
import viaduct.engine.runtime2.arbitrary.parseResolverTestSize
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.propertytest.NumberedResolverTestBatch
import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.propertytest.ResolverTestShardIds
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkNumberedBatch
import viaduct.engine.runtime2.propertytest.numberBatches

data class CandidateBatch(
    val id: String,
    val ids: ResolverTestShardIds,
    val batch: ResolverTestBatch,
)

/** One side of a suite comparison: a checked-in suite, or its definition regenerated in memory with another seed or size. */
sealed interface CoverageCandidate {
    val definition: ResolverTestSuiteDefinition
    val label: String

    /** Does any work that precedes replay, such as generating batches, so replay timing measures replay alone. */
    suspend fun prepare() {}

    /** Replays every case with its stable ID and the schema ID of its batch, returning the number of cases expected to run. */
    suspend fun replay(property: suspend (TestWorld, ResolverTestCase, CoverageTestId) -> Unit): Int

    /** The batches [replay] runs, in order, with the stable IDs their cases carry. */
    suspend fun batches(): List<CandidateBatch>

    class Stored(
        val suite: ResolverTestSuite,
        val selected: ResolverTestCaseId? = null,
        val selectedBatchId: String? = null,
    ) : CoverageCandidate {
        init {
            require(selected == null || selectedBatchId == null) { "Select either one case or one batch, not both" }
            require(selectedBatchId == null || suite.batches.any { it.id == selectedBatchId }) {
                "Resolver test suite ${suite.definition.id} holds no test cases in batch $selectedBatchId"
            }
        }

        override val definition: ResolverTestSuiteDefinition get() = suite.definition
        override val label: String get() = definition.id

        override suspend fun replay(property: suspend (TestWorld, ResolverTestCase, CoverageTestId) -> Unit): Int {
            val idsByBatch = suite.batches.associate { it.id to it.ids() }
            suite.replay(selected = selected, selectedBatchId = selectedBatchId) { world, testCase ->
                val id = ResolverTestCaseId.parse(requireNotNull(testCase.testCaseId) { "Saved test case has no stable ID" })
                property(world, testCase, CoverageTestId.from(id, idsByBatch.getValue(id.batch)))
            }
            return when {
                selectedBatchId != null -> idsByBatch.getValue(selectedBatchId).queries.size
                selected != null -> 1
                else -> suite.caseCount
            }
        }

        override suspend fun batches(): List<CandidateBatch> = suite.batches.map { stored -> CandidateBatch(stored.id, stored.ids(), suite.loadShard(stored.id)) }
    }

    /** Numbers batches as the materializer does, so a promoted candidate keeps the batch IDs measured here. */
    class Generated(
        override val definition: ResolverTestSuiteDefinition,
        val seed: Long = definition.seed,
        val counts: TestCaseCount = definition.counts,
    ) : CoverageCandidate {
        override val label: String get() = "${definition.id}@seed=$seed,size=${counts.summary()}"

        private var generated: List<NumberedResolverTestBatch>? = null

        override suspend fun prepare() {
            if (generated == null) generated = definition.numberBatches(generateResolverTestBatches(counts, definition.config, seed))
        }

        override suspend fun replay(property: suspend (TestWorld, ResolverTestCase, CoverageTestId) -> Unit): Int {
            prepare()
            val batches = checkNotNull(generated)
            batches.forEachIndexed { offset, numbered ->
                definition.checkNumberedBatch(
                    batchId = numbered.id,
                    batch = numbered.batch,
                    queries = numbered.ids.queries,
                    schemaIndex = offset + 1,
                    seed = seed,
                    counts = counts,
                ) { world, testCase ->
                    property(world, testCase, CoverageTestId.from(ResolverTestCaseId.parse(requireNotNull(testCase.testCaseId)), numbered.ids))
                }
            }
            return batches.sumOf { it.batch.queries.size }
        }

        override suspend fun batches(): List<CandidateBatch> {
            prepare()
            return checkNotNull(generated).map { CandidateBatch(it.id, it.ids, it.batch) }
        }
    }

    companion object {
        /** Parses `<suite-id>` for the stored suite, or `<suite-id>@seed=<n>,size=<S:Q>` with either override to regenerate it. */
        fun parse(spec: String): CoverageCandidate {
            val id = spec.substringBefore('@')
            val definition =
                requireNotNull(ResolverTestSuiteDefinition.entries.singleOrNull { it.id == id }) {
                    "Unknown resolver test suite $id; known suites: ${ResolverTestSuiteDefinition.entries.joinToString { it.id }}"
                }
            if ('@' !in spec) return Stored(ResolverTestSuite.load(definition))
            val overrides =
                spec.substringAfter('@').split(',').associate { override ->
                    val (key, value) = override.split('=', limit = 2).also { require(it.size == 2) { "Candidate override must be key=value: $override" } }
                    key to value
                }
            require(overrides.keys.all { it in setOf("seed", "size") } && overrides.isNotEmpty()) {
                "Candidate $spec may override only seed and size"
            }
            return Generated(
                definition = definition,
                seed = overrides["seed"]?.let { requireNotNull(it.toLongOrNull()) { "Candidate seed must be an integer: $it" } } ?: definition.seed,
                counts = overrides["size"]?.let(::parseResolverTestSize) ?: definition.counts,
            )
        }
    }
}
