package viaduct.engine.runtime2.coverage

/**
 * The batches chosen from a candidate pool, the distinct-case and distinct-batch support they keep for each requirement,
 * and the cases they keep for each obligation combination the pool produced.
 */
data class ResolverCoverageBatchSelection(
    val criteria: ResolverCoverageScoringCriteria,
    val selectedBatchIds: List<String>,
    val support: Map<ResolverCoverageRequirement, Int>,
    val poolSupport: Map<ResolverCoverageRequirement, Int>,
    val batchSupport: Map<ResolverCoverageRequirement, Int>,
    val poolBatchSupport: Map<ResolverCoverageRequirement, Int>,
    val combinationSupport: Map<Set<String>, Int>,
    val poolCombinationSupport: Map<Set<String>, Int>,
    val minimumBatches: Int = 1,
    val minimumCombinationCases: Int = 1,
) {
    val poolCombinations: Set<Set<String>> get() = poolCombinationSupport.keys

    val coveredCombinations: Set<Set<String>> get() = combinationSupport.filterValues { it > 0 }.keys

    val uncoveredCombinations: Set<Set<String>> get() = poolCombinations - coveredCombinations

    val unmetRequirements: Set<ResolverCoverageRequirement>
        get() = criteria.requirements.filterTo(linkedSetOf()) { support.getValue(it) < it.minimumDistinctCases }

    /** Requirements that no subset of the pool can meet, so only a different pool can satisfy them. */
    val infeasibleRequirements: Set<ResolverCoverageRequirement>
        get() = criteria.requirements.filterTo(linkedSetOf()) { poolSupport.getValue(it) < it.minimumDistinctCases }

    /** Combinations kept by fewer cases than [minimumCombinationCases] although the pool has that many; one case per combination is brittle. */
    val underSupportedCombinations: Set<Set<String>>
        get() = poolCombinations.filterTo(linkedSetOf()) { combinationSupport.getValue(it) < minOf(minimumCombinationCases, poolCombinationSupport.getValue(it)) }

    /** Requirements held by fewer batches than [minimumBatches] although the pool has enough batches holding them. */
    val underspreadRequirements: Set<ResolverCoverageRequirement>
        get() = batchSupport.filterTo(linkedMapOf()) { (requirement, batches) -> batches < minOf(minimumBatches, poolBatchSupport.getValue(requirement)) }.keys
}

/**
 * Greedily picks the batch that closes the most remaining shortfall, preferring fewer cases and then the earlier batch,
 * and finally drops any picked batch whose removal keeps every goal's attainable support. Each requirement is a goal of
 * its minimum distinct cases and a goal of [minimumBatches] distinct batches, so one rich batch cannot carry it alone.
 * With [coverEveryCombination], each obligation combination observed in the pool is a goal of [minimumCombinationCases] cases.
 */
fun selectCoverageBatches(
    signatures: List<ResolverCoverageSignature>,
    criteria: ResolverCoverageScoringCriteria,
    coverEveryCombination: Boolean = false,
    minimumBatches: Int = 1,
    minimumCombinationCases: Int = 1,
): ResolverCoverageBatchSelection {
    require(minimumCombinationCases > 0) { "Each combination needs at least one case" }
    val requirements = criteria.requirements.sortedWith(compareBy({ it.kind }, { it.id }))
    val poolCombinationSupport = signatures.groupingBy { it.obligations }.eachCount()
    val caseGoals = requirements.map { requirement -> SelectionGoal(requirement, requirement.minimumDistinctCases) { it.supporting(requirement) } }
    val spreadGoals = requirements.map { requirement -> SelectionGoal(Spread(requirement), minimumBatches) { minOf(1, it.supporting(requirement)) } }
    val combinationGoals =
        if (coverEveryCombination) poolCombinationSupport.keys.map { combination -> SelectionGoal(combination, minimumCombinationCases) { it.combinations[combination] ?: 0 } } else emptyList()
    val goals: List<SelectionGoal<*>> = caseGoals + spreadGoals + combinationGoals
    val candidates =
        signatures.groupBy { it.test.batchId }.map { (batchId, cases) ->
            val batch = BatchCases(cases)
            BatchCandidate(batchId, cases.size, goals.associateWith { it.measure(batch) }, batch.combinations)
        }.sortedBy { it.batchId }
    val target = goals.associateWith { goal -> minOf(goal.minimum, candidates.sumOf { it.support.getValue(goal) }) }

    val selected = mutableListOf<BatchCandidate>()
    val totals = goals.associateWithTo(mutableMapOf()) { 0 }
    val remaining = candidates.toMutableList()
    while (remaining.isNotEmpty()) {
        val gains = remaining.associateWith { candidate -> goals.sumOf { goal -> minOf(target.getValue(goal) - totals.getValue(goal), candidate.support.getValue(goal)).coerceAtLeast(0) } }
        val best = remaining.maxWith(compareBy<BatchCandidate> { gains.getValue(it) }.thenByDescending { it.cases }.thenByDescending { it.batchId })
        if (gains.getValue(best) == 0) break
        selected += best
        remaining -= best
        goals.forEach { goal -> totals[goal] = totals.getValue(goal) + best.support.getValue(goal) }
    }
    for (candidate in selected.reversed()) {
        if (goals.all { goal -> totals.getValue(goal) - candidate.support.getValue(goal) >= target.getValue(goal) }) {
            selected -= candidate
            goals.forEach { goal -> totals[goal] = totals.getValue(goal) - candidate.support.getValue(goal) }
        }
    }

    val kept = selected.sortedBy { it.batchId }

    fun <K> List<SelectionGoal<K>>.supportIn(batches: List<BatchCandidate>): Map<K, Int> = associate { goal -> goal.key to batches.sumOf { it.support.getValue(goal) } }
    return ResolverCoverageBatchSelection(
        criteria = criteria,
        selectedBatchIds = kept.map { it.batchId },
        support = caseGoals.supportIn(kept),
        poolSupport = caseGoals.supportIn(candidates),
        batchSupport = spreadGoals.supportIn(kept).mapKeys { it.key.requirement },
        poolBatchSupport = spreadGoals.supportIn(candidates).mapKeys { it.key.requirement },
        combinationSupport = poolCombinationSupport.keys.associateWith { combination -> kept.sumOf { it.combinations[combination] ?: 0 } },
        poolCombinationSupport = poolCombinationSupport,
        minimumBatches = minimumBatches,
        minimumCombinationCases = minimumCombinationCases,
    )
}

/** Keeps only the measured cases of [batchIds], as if those batches had been replayed alone. */
fun ResolverCoverageMeasurement.retainingBatches(batchIds: Collection<String>): ResolverCoverageMeasurement {
    val retained = batchIds.toSet()
    return copy(
        executions = executions.filter { it.test.batchId in retained },
        rows = rows.filter { it.batchId in retained },
        dependencies = dependencies.filter { it.batchId in retained },
    )
}

/** Whether this case counts toward [requirement], by obligation or by resolver attribute. */
fun ResolverCoverageSignature.supports(requirement: ResolverCoverageRequirement): Boolean =
    when (requirement.kind) {
        ResolverCoverageRequirementKind.OBLIGATION -> requirement.id in obligations
        ResolverCoverageRequirementKind.ATTRIBUTE -> requirement.id in attributes
    }

/** A requirement's distinct-batch goal, kept apart from its distinct-case goal of the same requirement. */
private data class Spread(val requirement: ResolverCoverageRequirement)

private class SelectionGoal<K>(
    val key: K,
    val minimum: Int,
    val measure: (BatchCases) -> Int,
)

private class BatchCases(val cases: List<ResolverCoverageSignature>) {
    val combinations: Map<Set<String>, Int> = cases.groupingBy { it.obligations }.eachCount()

    fun supporting(requirement: ResolverCoverageRequirement): Int = cases.count { it.supports(requirement) }
}

private class BatchCandidate(
    val batchId: String,
    val cases: Int,
    val support: Map<SelectionGoal<*>, Int>,
    val combinations: Map<Set<String>, Int>,
)
