package viaduct.engine.runtime2.resolution

import viaduct.engine.runtime2.model.Assumptions
import viaduct.graphql.schema.ViaductSchema

internal enum class ParentProducerShape {
    SINGULAR,
    LIST,
    NESTED_LIST,
}

internal enum class ParentTargetKind {
    CONCRETE,
    ABSTRACT,
}

internal enum class ParentResolverPlacement {
    DIRECT,
    TRANSITIVE,
}

internal enum class ParentArgumentSelectionDepth {
    DIRECT,
    NESTED,
}

internal data class ParentVariableFragment(
    val source: ParentVariableSource,
    val fragment: ParentResolverInputFragment,
)

internal data class ParentFocusedCoverageSnapshot(
    val parentDepths: Set<Int> = emptySet(),
    val producerShapes: Set<ParentProducerShape> = emptySet(),
    val targetKinds: Set<ParentTargetKind> = emptySet(),
    val resolverPlacements: Set<ParentResolverPlacement> = emptySet(),
    val variableSources: Set<ParentVariableSource> = emptySet(),
    val mixedVariableSourcePairs: Set<Set<ParentVariableSource>> = emptySet(),
    val inputFragments: Set<ParentResolverInputFragment> = emptySet(),
    val argumentSelectionDepths: Set<ParentArgumentSelectionDepth> = emptySet(),
    val diagonalDepths: Set<Int> = emptySet(),
    val diagonalVariableFragments: Set<ParentVariableFragment> = emptySet(),
    val sometimesPassiveParentDemandOccurrences: Int = 0,
    val sometimesPassiveParentDemandDepths: Set<Int> = emptySet(),
) {
    operator fun plus(other: ParentFocusedCoverageSnapshot): ParentFocusedCoverageSnapshot =
        ParentFocusedCoverageSnapshot(
            parentDepths = parentDepths + other.parentDepths,
            producerShapes = producerShapes + other.producerShapes,
            targetKinds = targetKinds + other.targetKinds,
            resolverPlacements = resolverPlacements + other.resolverPlacements,
            variableSources = variableSources + other.variableSources,
            mixedVariableSourcePairs = mixedVariableSourcePairs + other.mixedVariableSourcePairs,
            inputFragments = inputFragments + other.inputFragments,
            argumentSelectionDepths = argumentSelectionDepths + other.argumentSelectionDepths,
            diagonalDepths = diagonalDepths + other.diagonalDepths,
            diagonalVariableFragments =
                diagonalVariableFragments + other.diagonalVariableFragments,
            sometimesPassiveParentDemandOccurrences =
                sometimesPassiveParentDemandOccurrences +
                    other.sometimesPassiveParentDemandOccurrences,
            sometimesPassiveParentDemandDepths =
                sometimesPassiveParentDemandDepths +
                    other.sometimesPassiveParentDemandDepths,
        )
}

internal fun parentFocusedCoverageSnapshot(
    world: Assumptions,
    parents: List<ParentSelectionSetCoverage>,
): ParentFocusedCoverageSnapshot {
    val selectedResolvers = parents.flatMap(ParentSelectionSetCoverage::selectedResolvers)
    val variableArguments =
        selectedResolvers.flatMap(ParentSelectedResolverCoverage::variableArgumentSelections)
    val diagonals = selectedResolvers.filter { selected -> selected.diagonalParentDepth > 0 }
    return ParentFocusedCoverageSnapshot(
        parentDepths = parents.mapTo(linkedSetOf()) { parent -> parent.consecutiveParentDepth },
        producerShapes =
            parents.mapTo(linkedSetOf()) { parent ->
                when (world.parentFieldRelations.getValue(parent.field).type.listDepth) {
                    0 -> ParentProducerShape.SINGULAR
                    1 -> ParentProducerShape.LIST
                    else -> ParentProducerShape.NESTED_LIST
                }
            },
        targetKinds =
            parents.mapTo(linkedSetOf()) { parent ->
                if (parent.field.type.baseTypeDef is ViaductSchema.Object) {
                    ParentTargetKind.CONCRETE
                } else {
                    ParentTargetKind.ABSTRACT
                }
            },
        resolverPlacements =
            selectedResolvers.mapTo(linkedSetOf()) { selected ->
                if (selected.selectionDepthBelowParent == 1) {
                    ParentResolverPlacement.DIRECT
                } else {
                    ParentResolverPlacement.TRANSITIVE
                }
            },
        variableSources =
            selectedResolvers.flatMapTo(linkedSetOf()) { selected ->
                selected.requiredInputVariableSources
            },
        mixedVariableSourcePairs =
            selectedResolvers.flatMapTo(linkedSetOf()) { selected ->
                selected.requiredInputVariableSources.allPairs()
            },
        inputFragments =
            variableArguments.mapTo(linkedSetOf()) { argument -> argument.fragment },
        argumentSelectionDepths =
            variableArguments.mapTo(linkedSetOf()) { argument ->
                if (argument.selectionDepth == 1) {
                    ParentArgumentSelectionDepth.DIRECT
                } else {
                    ParentArgumentSelectionDepth.NESTED
                }
            },
        diagonalDepths = diagonals.mapTo(linkedSetOf()) { selected -> selected.diagonalParentDepth },
        diagonalVariableFragments =
            diagonals.flatMapTo(linkedSetOf()) { selected ->
                selected.variableArgumentSelections.flatMap { argument ->
                    argument.variableSources.map { source ->
                        ParentVariableFragment(source, argument.fragment)
                    }
                }
            },
    )
}

internal data class ParentFocusedCoverageCriterion(
    val number: Int,
    val name: String,
    val hit: Boolean,
    val detail: String,
)

private fun ParentFocusedCoverageSnapshot.hasCriterionInstance(number: Int): Boolean =
    when (number) {
        1 -> parentDepths.isNotEmpty()
        2 -> resolverPlacements.isNotEmpty()
        3 -> variableSources.isNotEmpty()
        4 -> mixedVariableSourcePairs.isNotEmpty()
        5 -> inputFragments.isNotEmpty()
        6 -> argumentSelectionDepths.isNotEmpty()
        7 -> diagonalDepths.isNotEmpty()
        8 -> diagonalVariableFragments.isNotEmpty()
        9 -> sometimesPassiveParentDemandOccurrences > 0
        else -> error("Unknown parent-focused coverage criterion $number")
    }

internal fun ParentFocusedCoverageSnapshot.criteria(): List<ParentFocusedCoverageCriterion> {
    val expectedDepths = setOf(1, 2, 3)
    val expectedProducerShapes = ParentProducerShape.entries.toSet()
    val expectedTargetKinds = ParentTargetKind.entries.toSet()
    val expectedPlacements = ParentResolverPlacement.entries.toSet()
    // The broad stress generator does not yet create variables-provider functions.
    val expectedSources =
        setOf(
            ParentVariableSource.ARGUMENT,
            ParentVariableSource.OBJECT_FIELD,
            ParentVariableSource.QUERY_FIELD,
        )
    val expectedPairs = expectedSources.allPairs()
    val expectedFragments = ParentResolverInputFragment.entries.toSet()
    val expectedArgumentDepths = ParentArgumentSelectionDepth.entries.toSet()
    val expectedDiagonalVariableFragments =
        expectedSources.flatMapTo(linkedSetOf()) { source ->
            expectedFragments.map { fragment -> ParentVariableFragment(source, fragment) }
        }
    return listOf(
        criterion(
            number = 1,
            name = "Parent topology",
            missing =
                missing("depths", expectedDepths, parentDepths) +
                    missing("producerShapes", expectedProducerShapes, producerShapes) +
                    missing("targetKinds", expectedTargetKinds, targetKinds),
            observed =
                "depths=$parentDepths, producerShapes=$producerShapes, targetKinds=$targetKinds",
        ),
        criterion(
            number = 2,
            name = "Resolver placement",
            missing = missing("placements", expectedPlacements, resolverPlacements),
            observed = "placements=$resolverPlacements",
        ),
        criterion(
            number = 3,
            name = "Variable sources",
            missing = missing("sources", expectedSources, variableSources),
            observed = "sources=$variableSources",
        ),
        criterion(
            number = 4,
            name = "Mixed sources",
            missing = missing("pairs", expectedPairs, mixedVariableSourcePairs),
            observed = "pairs=$mixedVariableSourcePairs",
        ),
        criterion(
            number = 5,
            name = "Input locations",
            missing = missing("fragments", expectedFragments, inputFragments),
            observed = "fragments=$inputFragments",
        ),
        criterion(
            number = 6,
            name = "Argument-selection depths",
            missing = missing("depths", expectedArgumentDepths, argumentSelectionDepths),
            observed = "depths=$argumentSelectionDepths",
        ),
        criterion(
            number = 7,
            name = "Diagonals",
            missing =
                buildList {
                    if (1 !in diagonalDepths) add("depth 1")
                    if (diagonalDepths.none { depth -> depth >= 2 }) add("depth 2+")
                },
            observed = "depths=$diagonalDepths",
        ),
        criterion(
            number = 8,
            name = "Variable-bearing diagonals",
            missing =
                missing(
                    "source/fragment",
                    expectedDiagonalVariableFragments,
                    diagonalVariableFragments,
                ),
            observed = "sourceFragments=$diagonalVariableFragments",
        ),
        criterion(
            number = 9,
            name = "Sometimes-passive parent demand",
            missing =
                if (sometimesPassiveParentDemandOccurrences == 0) {
                    listOf("source-supplied occurrences")
                } else {
                    emptyList()
                },
            observed =
                "occurrences=$sometimesPassiveParentDemandOccurrences, " +
                    "depths=$sometimesPassiveParentDemandDepths",
        ),
    )
}

internal class ParentFocusedCoverageReport(
    private val schemaCount: Int,
    private val sliceCount: Int,
) {
    init {
        require(schemaCount > 0)
        require(sliceCount > 0)
        require(schemaCount % sliceCount == 0) {
            "$schemaCount schemas cannot be divided into $sliceCount equal parent-focused runs"
        }
    }

    private val schemasPerSlice = schemaCount / sliceCount
    private val casesBySlice = IntArray(sliceCount)
    private val coverageBySlice =
        MutableList(sliceCount) { ParentFocusedCoverageSnapshot() }
    private val criterionInstancesBySlice =
        MutableList(sliceCount) { IntArray(ParentFocusedCoverageSnapshot().criteria().size) }

    fun record(
        schemaIndex: Int,
        snapshot: ParentFocusedCoverageSnapshot,
    ) {
        val index = sliceIndex(schemaIndex)
        casesBySlice[index] += 1
        coverageBySlice[index] = coverageBySlice[index] + snapshot
        snapshot.criteria().forEachIndexed { criterionIndex, criterion ->
            if (snapshot.hasCriterionInstance(criterion.number)) {
                criterionInstancesBySlice[index][criterionIndex] += 1
            }
        }
    }

    fun render(): String {
        val criteriaBySlice = coverageBySlice.map { coverage -> coverage.criteria() }
        val runReports =
            coverageBySlice.mapIndexed { index, coverage ->
                renderRun(
                    runNumber = index + 1,
                    firstSchema = index * schemasPerSlice + 1,
                    lastSchema = (index + 1) * schemasPerSlice,
                    cases = casesBySlice[index],
                    coverage = coverage,
                )
            }
        val hitRuns = criteriaBySlice.count { criteria -> criteria.all { it.hit } }
        val overall = combinedCoverage()
        return buildString {
            appendLine("Resolution parent-focused coverage report")
            runReports.forEach { report -> appendLine(report) }
            appendLine("COMBINED CRITERION COVERAGE:")
            criteriaBySlice.first().forEachIndexed { index, criterion ->
                val hitCount = criteriaBySlice.count { criteria -> criteria[index].hit }
                val instanceCounts = criterionInstancesBySlice.map { counts -> counts[index] }
                appendLine(
                    "  ${criterion.number}. ${criterion.name}: " +
                        "slices=$hitCount/$sliceCount, " +
                        "instances=${instanceCounts.sum()}/${casesBySlice.sum()} cases, " +
                        "bySlice=" +
                        instanceCounts.indices.joinToString(prefix = "[", postfix = "]") { slice ->
                            "${instanceCounts[slice]}/${casesBySlice[slice]}"
                        },
                )
            }
            appendLine(
                "FOUR-RUN RESULT: ${if (hitRuns == sliceCount) "HIT" else "MISS"} " +
                    "($hitRuns/$sliceCount runs hit all nine criteria)",
            )
            appendLine("COMBINED RESULT: ${if (overall.criteria().all { it.hit }) "HIT" else "MISS"}")
        }.trimEnd()
    }

    fun requireCombinedHit() {
        val missed = combinedCoverage().criteria().filterNot { criterion -> criterion.hit }
        check(missed.isEmpty()) {
            "Combined parent-focused coverage missed criteria: " +
                missed.joinToString { criterion -> "${criterion.number}. ${criterion.name}" }
        }
    }

    private fun combinedCoverage(): ParentFocusedCoverageSnapshot =
        coverageBySlice.fold(
            ParentFocusedCoverageSnapshot(),
            ParentFocusedCoverageSnapshot::plus,
        )

    private fun renderRun(
        runNumber: Int,
        firstSchema: Int,
        lastSchema: Int,
        cases: Int,
        coverage: ParentFocusedCoverageSnapshot,
    ): String =
        buildString {
            val criteria = coverage.criteria()
            appendLine(
                "RUN $runNumber/$sliceCount: " +
                    "${if (criteria.all { it.hit }) "HIT" else "MISS"} " +
                    "(schemas=$firstSchema..$lastSchema, cases=$cases)",
            )
            criteria.forEach { criterion ->
                append("  ${criterion.number}. ${criterion.name}: ")
                append(if (criterion.hit) "HIT" else "MISS")
                append(" — ${criterion.detail}")
                appendLine()
            }
        }.trimEnd()

    private fun sliceIndex(schemaIndex: Int): Int {
        require(schemaIndex in 1..schemaCount)
        return (schemaIndex - 1) / schemasPerSlice
    }
}

private fun criterion(
    number: Int,
    name: String,
    missing: List<String>,
    observed: String,
): ParentFocusedCoverageCriterion =
    ParentFocusedCoverageCriterion(
        number = number,
        name = name,
        hit = missing.isEmpty(),
        detail = if (missing.isEmpty()) observed else "missing=${missing.joinToString()}; $observed",
    )

private fun <T> missing(
    label: String,
    expected: Set<T>,
    observed: Set<T>,
): List<String> =
    (expected - observed).takeIf(Set<T>::isNotEmpty)?.let { missing ->
        listOf("$label=$missing")
    }.orEmpty()

private fun <T> Set<T>.allPairs(): Set<Set<T>> =
    flatMapIndexed { index, first ->
        drop(index + 1).map { second -> setOf(first, second) }
    }.toSet()
