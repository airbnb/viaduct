package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageTestId
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.ResolverCoverageSignature
import viaduct.engine.runtime2.coverage.retainingBatches
import viaduct.engine.runtime2.coverage.selectCoverageBatches

class ResolverCoverageBatchSelectionTest {
    @Test
    fun `selection drops an early pick that later picks make redundant`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1", "o2", "o3", "o4"),
                case("b002", "q1", "o1", "o2", "o5"),
                case("b003", "q1", "o3", "o4", "o6"),
            )

        val selection = selectCoverageBatches(signatures, criteria((1..6).associate { "o$it" to 1 }))

        assertEquals(listOf("suite/b002", "suite/b003"), selection.selectedBatchIds)
        assertEquals(emptySet<ResolverCoverageRequirement>(), selection.unmetRequirements)
    }

    @Test
    fun `selection counts distinct cases toward minimums and prefers the smaller batch on ties`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1"),
                case("b001", "q2", "o1"),
                case("b001", "q3"),
                case("b002", "q1", "o1"),
                case("b002", "q2", "o1"),
                case("b003", "q1", "o1"),
            )

        val selection = selectCoverageBatches(signatures, criteria(mapOf("o1" to 3)))

        assertEquals(listOf("suite/b002", "suite/b003"), selection.selectedBatchIds)
        assertEquals(3, selection.support.values.single())
    }

    @Test
    fun `selection keeps the attainable support of a requirement the pool cannot meet`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1"),
                case("b002", "q1", "o2"),
                case("b003", "q1"),
            )
        val criteria = criteria(mapOf("o1" to 2, "o2" to 1))

        val selection = selectCoverageBatches(signatures, criteria)

        val o1 = criteria.requirements.single { it.id == "o1" }
        assertEquals(listOf("suite/b001", "suite/b002"), selection.selectedBatchIds)
        assertEquals(setOf(o1), selection.unmetRequirements)
        assertEquals(setOf(o1), selection.infeasibleRequirements)
        assertEquals(1, selection.poolSupport.getValue(o1))
    }

    @Test
    fun `selection matches attribute requirements against case attributes`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1"),
                ResolverCoverageSignature(test("b002", "q1"), emptySet(), setOf("output_kind:error"), emptyList()),
            )
        val criteria =
            ResolverCoverageScoringCriteria(
                setOf(ResolverCoverageRequirement(ResolverCoverageRequirementKind.ATTRIBUTE, "output_kind:error", 1)),
            )

        assertEquals(listOf("suite/b002"), selectCoverageBatches(signatures, criteria).selectedBatchIds)
    }

    @Test
    fun `covering every combination adds batches whose obligation sets are new`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1"),
                case("b001", "q2", "o1"),
                case("b002", "q1", "o1", "o2"),
                case("b003", "q1", "o2"),
                case("b004", "q1"),
                case("b005", "q1", "o1", "o2"),
            )
        val criteria = criteria(mapOf("o1" to 1, "o2" to 1))

        val minimal = selectCoverageBatches(signatures, criteria)
        val diverse = selectCoverageBatches(signatures, criteria, coverEveryCombination = true)

        assertEquals(listOf("suite/b002"), minimal.selectedBatchIds)
        assertEquals(listOf("suite/b001", "suite/b002", "suite/b003", "suite/b004"), diverse.selectedBatchIds)
        assertEquals(setOf(setOf("o1"), setOf("o1", "o2"), setOf("o2"), emptySet()), diverse.coveredCombinations)
        assertEquals(emptySet<Set<String>>(), diverse.uncoveredCombinations)
    }

    @Test
    fun `a minimum case count per combination keeps a second case where the pool has one`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1"),
                case("b002", "q1", "o1"),
                case("b003", "q1", "o1", "o2"),
                case("b004", "q1", "o1", "o2"),
                case("b005", "q1", "o2"),
            )
        val criteria = criteria(mapOf("o1" to 1, "o2" to 1))

        val single = selectCoverageBatches(signatures, criteria, coverEveryCombination = true)
        val doubled = selectCoverageBatches(signatures, criteria, coverEveryCombination = true, minimumCombinationCases = 2)

        assertEquals(listOf("suite/b001", "suite/b003", "suite/b005"), single.selectedBatchIds)
        assertEquals(listOf("suite/b001", "suite/b002", "suite/b003", "suite/b004", "suite/b005"), doubled.selectedBatchIds)
        assertEquals(mapOf(setOf("o1") to 2, setOf("o1", "o2") to 2, setOf("o2") to 1), doubled.combinationSupport)
        assertEquals(mapOf(setOf("o1") to 2, setOf("o1", "o2") to 2, setOf("o2") to 1), doubled.poolCombinationSupport)
        assertEquals(emptySet<Set<String>>(), doubled.underSupportedCombinations)
        assertEquals(setOf(setOf("o1"), setOf("o1", "o2")), single.copy(minimumCombinationCases = 2).underSupportedCombinations)
        assertEquals(emptySet<Set<String>>(), single.underSupportedCombinations)
    }

    @Test
    fun `a minimum batch count spreads a requirement one rich batch meets alone`() {
        val signatures =
            listOf(
                case("b001", "q1", "o1"),
                case("b001", "q2", "o1"),
                case("b001", "q3", "o1"),
                case("b002", "q1", "o1"),
                case("b003", "q1", "o1"),
                case("b004", "q1", "o2"),
            )
        val criteria = criteria(mapOf("o1" to 3, "o2" to 1))

        val dense = selectCoverageBatches(signatures, criteria)
        val spread = selectCoverageBatches(signatures, criteria, minimumBatches = 3)

        assertEquals(listOf("suite/b001", "suite/b004"), dense.selectedBatchIds)
        assertEquals(listOf("suite/b001", "suite/b002", "suite/b003", "suite/b004"), spread.selectedBatchIds)
        val requirements = criteria.requirements.associateBy { it.id }
        assertEquals(mapOf(requirements.getValue("o1") to 3, requirements.getValue("o2") to 1), spread.batchSupport)
        assertEquals(emptySet<ResolverCoverageRequirement>(), spread.underspreadRequirements)
        assertEquals(setOf(requirements.getValue("o1")), dense.copy(minimumBatches = 3).underspreadRequirements)
    }

    @Test
    fun `retaining batches keeps only their executions`() {
        val kept = CoverageExecution("run", "execution-1", test("b001", "q1"))
        val dropped = CoverageExecution("run", "execution-2", test("b002", "q1"))

        val retained = ResolverCoverageMeasurement(listOf(kept, dropped), emptyList()).retainingBatches(setOf("suite/b001"))

        assertEquals(listOf(kept), retained.executions)
    }

    private fun criteria(minimums: Map<String, Int>) =
        ResolverCoverageScoringCriteria(
            minimums.mapTo(mutableSetOf()) { (id, minimum) -> ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, id, minimum) },
        )

    private fun case(
        batch: String,
        query: String,
        vararg obligations: String,
    ) = ResolverCoverageSignature(test(batch, query), obligations.toSet(), emptySet(), emptyList())

    private fun test(
        batch: String,
        query: String,
    ) = CoverageTestId("suite/$batch", "schema-$batch", "registry", query)
}
