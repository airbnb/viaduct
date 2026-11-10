package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.coverage.ArgumentState
import viaduct.engine.runtime2.coverage.CoverageEvidenceKind
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageSupport
import viaduct.engine.runtime2.coverage.CoverageSupportChange
import viaduct.engine.runtime2.coverage.DeclaredOutputShape
import viaduct.engine.runtime2.coverage.OccurrenceCoverageObligation
import viaduct.engine.runtime2.coverage.OutputKind
import viaduct.engine.runtime2.coverage.RESOLVER_COVERAGE_MODEL_VERSION
import viaduct.engine.runtime2.coverage.RFC_271_CORE_OBLIGATION_IDS
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverBindingSource
import viaduct.engine.runtime2.coverage.ResolverCoverageCandidateComparison
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageMetricComparison
import viaduct.engine.runtime2.coverage.ResolverCoverageReportJson
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverCoverageScoreChange
import viaduct.engine.runtime2.coverage.ResolverCoverageScoreRelation
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.ResolverCoverageSuiteSetCriteria
import viaduct.engine.runtime2.coverage.ResolverCoverageSuiteSetRelation
import viaduct.engine.runtime2.coverage.ResolverDependencyOccurrence
import viaduct.engine.runtime2.coverage.ResolverDependencyRow
import viaduct.engine.runtime2.coverage.ResolverLocus
import viaduct.engine.runtime2.coverage.StampClass
import viaduct.engine.runtime2.coverage.combineCoverageMeasurements
import viaduct.engine.runtime2.coverage.compareCandidate
import viaduct.engine.runtime2.coverage.compareSuiteSetCandidate
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome

class ResolverCoverageMeasurementTest {
    @Test
    fun `one resolver occurrence can use both argument and object field sources`() {
        assertEquals(CoverageSupport(2, 1, 1, 1, 1, 1), measurement(workedExampleRows()).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `list object-field error requires all attributes on the same occurrence`() {
        val row =
            row("E1", "list-error", OBJECT_FIELD_ONLY).copy(
                locus = ResolverLocus.LIST_ELEMENT_OBJECT,
                outputKind = OutputKind.ERROR,
            )

        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(row)).listObjectFieldError())
    }

    @Test
    fun `resolver output is a descendant when result paths are nested`() {
        val ancestor = row("E1", "parent", emptySet(), "Item.parent").copy(resultPath = resultPath("Item.parent"))
        val descendant =
            row("E1", "child", emptySet(), "Parent.child").copy(
                resultPath = resultPath("Item.parent", "Parent.child"),
            )

        assertEquals(CoverageSupport(2, 1, 1, 1, 1, 1), measurement(listOf(ancestor, descendant)).resolverOutputContainsDescendant())
    }

    @Test
    fun `provider read across different list depths records list boundary`() {
        val provider = row("E1", "provider", emptySet(), "Source.value").copy(listAncestorCount = 0)
        val consumer = row("E1", "consumer", emptySet(), "Item.value").copy(listAncestorCount = 1)
        val edge = dependency(provider.execution, "provider", "Source.value", "consumer", "Item.value", "source")

        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(provider, consumer), listOf(edge)).providerReadCrossesListBoundary())
    }

    @Test
    fun `dependency diamond requires both paths between the same endpoints`() {
        val source = row("E1", "source", emptySet(), "Source.value")
        val left = row("E1", "left", emptySet(), "Item.left")
        val right = row("E1", "right", emptySet(), "Item.right")
        val sink = row("E1", "sink", emptySet(), "Item.sink")
        val edges =
            listOf(
                dependency(source.execution, "source", "Source.value", "left", "Item.left", "left"),
                dependency(source.execution, "source", "Source.value", "right", "Item.right", "right"),
                dependency(source.execution, "left", "Item.left", "sink", "Item.sink", "left-to-sink"),
                dependency(source.execution, "right", "Item.right", "sink", "Item.sink", "right-to-sink"),
            )

        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(source, left, right, sink), edges).providerDependencyDiamond())
    }

    @Test
    fun `null or error provider outcome is counted only on a recorded read`() {
        val provider = row("E1", "provider", emptySet(), "Source.value")
        val consumer = row("E1", "consumer", emptySet(), "Item.value")
        val edge = dependency(provider.execution, "provider", "Source.value", "consumer", "Item.value", "source")
            .copy(providerOutcome = ProviderCellOutcome.ERROR)

        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(provider, consumer), listOf(edge)).providerNullOrErrorReachesConsumer())
    }

    @Test
    fun `report records the coverage model version`() {
        val report = emptyReport()
        assertEquals(RESOLVER_COVERAGE_MODEL_VERSION, report.modelVersion)
    }

    @Test
    fun `empty report has no executions`() {
        val report = emptyReport()
        assertTrue(report.executions.isEmpty())
    }

    @Test
    fun `report separates obligation evidence from resolver attributes`() {
        val report = emptyReport()
        assertEquals(
            MetricAndAttributeCatalog(
                metrics =
                    mapOf(
                        "both_sources_on_one_occurrence" to CoverageEvidenceKind.LOCAL_COMBINATION,
                        "shared_provider_fan_out" to CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                        "multiple_provider_fan_in" to CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                        "both_sources_within_one_execution" to CoverageEvidenceKind.EXECUTION_COEXISTENCE,
                        "successful_and_failing_branches_within_one_execution" to CoverageEvidenceKind.EXECUTION_COEXISTENCE,
                        "list_object_field_error" to CoverageEvidenceKind.LOCAL_COMBINATION,
                        "resolver_output_contains_descendant" to CoverageEvidenceKind.RESULT_TREE_PATH,
                        "descendant_reads_ancestor" to CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                        "provider_read_crosses_list_boundary" to CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                        "provider_dependency_diamond" to CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                        "provider_null_or_error_reaches_consumer" to CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                    ),
                attributes =
                    setOf(
                        "resolution_role:invoked",
                        "resolution_role:source_supplied",
                        "resolution_role:argument_error_not_invoked",
                        "resolution_role:unknown",
                        "resolver_locus:query_root",
                        "resolver_locus:list_element_object",
                        "resolver_locus:other_object",
                        "stamp_class:variable_free",
                        "stamp_class:occurrence_stamped",
                        "provider_path_depth:none",
                        "argument_state:none",
                        "argument_state:resolved",
                        "argument_state:contains_null",
                        "argument_state:error",
                        "declared_output_shape:scalar",
                        "declared_output_shape:object",
                        "declared_output_shape:list",
                        "output_kind:scalar",
                        "output_kind:object",
                        "output_kind:list",
                        "output_kind:null",
                        "output_kind:error",
                    ) + bindingSourceAttributeIds(),
            ),
            MetricAndAttributeCatalog(
                metrics = report.metrics.associate { it.id to it.evidenceKind },
                attributes = report.attributes.mapTo(sortedSetOf()) { it.id },
            ),
        )
    }

    @Test
    fun `resolver attributes are separate from obligations and can be required`() {
        val row = row("E1", "A", ARGUMENT_AND_OBJECT_FIELD).copy(
            locus = ResolverLocus.QUERY_ROOT,
            stampClass = StampClass.OCCURRENCE_STAMPED,
            listAncestorCount = 2,
            maxProviderPathDepth = 2,
            argumentState = ArgumentState.CONTAINS_NULL,
            declaredOutputShape = DeclaredOutputShape.LIST,
            outputKind = OutputKind.ERROR,
        )
        val report = measurement(listOf(row)).report()
        val selectedFeatures = setOf(
            "resolver_locus:query_root",
            "stamp_class:occurrence_stamped",
            "list_ancestor_count:2",
            "binding_sources:argument+object_field",
            "provider_path_depth:2",
            "argument_state:contains_null",
            "declared_output_shape:list",
            "output_kind:error",
        )
        val attributeCriteria =
            ResolverCoverageScoringCriteria(
                setOf(
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.ATTRIBUTE, "output_kind:error", 1),
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "both_sources_on_one_occurrence", 1),
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "both_sources_within_one_execution", 1),
                )
            )
        val comparison = report.compareCandidate(emptyReport(), attributeCriteria)
        val score = comparison.baselineScore

        assertEquals(
            AttributeSupportSummary(
                support = selectedFeatures.associateWith { CoverageSupport(1, 1, 1, 1, 1, 1) },
                includedInSignature = false,
                supportedObligationCount = 2,
                scoredOutputKindSupport = 1,
                candidateRejectedForMissingAttribute = true,
            ),
            AttributeSupportSummary(
                support = report.attributes.filter { it.id in selectedFeatures }.associate { it.id to it.support },
                includedInSignature = selectedFeatures.all { it in report.analysis.signatures.single().obligations },
                supportedObligationCount = score.supportedObligations.size,
                scoredOutputKindSupport = score.attributeTestSupport.getValue("output_kind:error"),
                candidateRejectedForMissingAttribute =
                    comparison.scoreChange.relation == ResolverCoverageScoreRelation.INFEASIBLE &&
                        ResolverCoverageRequirement(
                            ResolverCoverageRequirementKind.ATTRIBUTE,
                            "output_kind:error",
                            1,
                        ) in comparison.scoreChange.unmetRequirements,
            ),
        )
    }

    @Test
    fun `unobserved dynamic resolver attributes are infeasible requirements`() {
        val requirements = setOf(
            ResolverCoverageRequirement(
                ResolverCoverageRequirementKind.ATTRIBUTE,
                "list_ancestor_count:17",
                1,
            ),
            ResolverCoverageRequirement(
                ResolverCoverageRequirementKind.ATTRIBUTE,
                "provider_path_depth:23",
                1,
            ),
        )
        val criteria = ResolverCoverageScoringCriteria(requirements)

        val comparison = emptyReport().compareCandidate(emptyReport(), criteria)

        assertEquals(ResolverCoverageScoreRelation.INFEASIBLE, comparison.scoreChange.relation)
        assertEquals(requirements, comparison.scoreChange.unmetRequirements)
    }

    @Test
    fun `empty report gives every metric zero support`() {
        val report = emptyReport()
        assertTrue((report.metrics.map { it.support } + report.attributes.map { it.support }).all { it == CoverageSupport(0, 0, 0, 0, 0, 0) })
    }

    @Test
    fun `candidate comparison keeps local and dependency support separate`() {
        val comparison = comparisonWithoutDependencyEdges()

        assertEquals(
            listOf(
                MetricComparisonSummary(
                    id = "both_sources_on_one_occurrence",
                    evidenceKind = CoverageEvidenceKind.LOCAL_COMBINATION,
                    baselineSupport = ZERO_SUPPORT,
                    candidateSupport = CoverageSupport(2, 1, 1, 1, 1, 1),
                    supportChange = CoverageSupportChange(2, 1, 1, 1, 1, 1),
                    newlySupported = true,
                    noLongerSupported = false,
                ),
                MetricComparisonSummary(
                    id = "shared_provider_fan_out",
                    evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                    baselineSupport = ZERO_SUPPORT,
                    candidateSupport = ZERO_SUPPORT,
                    supportChange = ZERO_SUPPORT_CHANGE,
                    newlySupported = false,
                    noLongerSupported = false,
                ),
                MetricComparisonSummary(
                    id = "multiple_provider_fan_in",
                    evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                    baselineSupport = ZERO_SUPPORT,
                    candidateSupport = ZERO_SUPPORT,
                    supportChange = ZERO_SUPPORT_CHANGE,
                    newlySupported = false,
                    noLongerSupported = false,
                ),
            ),
            comparison.metrics
                .filter { it.id in setOf("both_sources_on_one_occurrence", "shared_provider_fan_out", "multiple_provider_fan_in") }
                .map { it.summary() },
        )
    }

    @Test
    fun `dependency edges add fan out and fan in support independently`() {
        val comparison = dependencyComparison()
        val expectedSupport = CoverageSupport(1, 1, 1, 1, 1, 1)
        val expectedChange = CoverageSupportChange(1, 1, 1, 1, 1, 1)

        assertEquals(
            listOf(
                MetricComparisonSummary(
                    id = "shared_provider_fan_out",
                    evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                    baselineSupport = ZERO_SUPPORT,
                    candidateSupport = expectedSupport,
                    supportChange = expectedChange,
                    newlySupported = true,
                    noLongerSupported = false,
                ),
                MetricComparisonSummary(
                    id = "multiple_provider_fan_in",
                    evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
                    baselineSupport = ZERO_SUPPORT,
                    candidateSupport = expectedSupport,
                    supportChange = expectedChange,
                    newlySupported = true,
                    noLongerSupported = false,
                ),
            ),
            comparison.metrics.filter { it.id == "shared_provider_fan_out" || it.id == "multiple_provider_fan_in" }.map { it.summary() },
        )
    }

    @Test
    fun `removing the last supporting test reports the full support loss`() {
        val comparison = measurement(workedExampleRows()).report().compareCandidate(measurement(emptyList()).report(), coreScoringCriteria())
        val localCombination = comparison.metrics.single { it.id == "both_sources_on_one_occurrence" }

        assertEquals(
            MetricComparisonSummary(
                id = "both_sources_on_one_occurrence",
                evidenceKind = CoverageEvidenceKind.LOCAL_COMBINATION,
                baselineSupport = CoverageSupport(2, 1, 1, 1, 1, 1),
                candidateSupport = ZERO_SUPPORT,
                supportChange = CoverageSupportChange(-2, -1, -1, -1, -1, -1),
                newlySupported = false,
                noLongerSupported = true,
            ),
            localCombination.summary(),
        )
    }

    @Test
    fun `hard gate rejects a candidate that misses selected requirements`() {
        val comparison = emptyReport().compareCandidate(measurement(workedExampleRows()).report(), coreScoringCriteria())

        assertEquals(
            ResolverCoverageScoreChange(
                obligationTestSupport =
                    mapOf(
                        "both_sources_on_one_occurrence" to 1,
                        "shared_provider_fan_out" to 0,
                        "multiple_provider_fan_in" to 0,
                        "both_sources_within_one_execution" to 2,
                        "successful_and_failing_branches_within_one_execution" to 0,
                    ),
                attributeTestSupport = emptyMap(),
                relation = ResolverCoverageScoreRelation.INFEASIBLE,
                unmetRequirements = setOf(
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "shared_provider_fan_out", 1),
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "multiple_provider_fan_in", 1),
                    ResolverCoverageRequirement(
                        ResolverCoverageRequirementKind.OBLIGATION,
                        "successful_and_failing_branches_within_one_execution",
                        1,
                    ),
                ),
            ),
            comparison.scoreChange,
        )
    }

    @Test
    fun `hard gate enforces the minimum number of distinct supporting cases`() {
        val requirement =
            ResolverCoverageRequirement(
                ResolverCoverageRequirementKind.OBLIGATION,
                "both_sources_on_one_occurrence",
                minimumDistinctCases = 2,
            )
        val comparison =
            emptyReport().compareCandidate(
                measurement(listOf(row("E1", "A", ARGUMENT_AND_OBJECT_FIELD))).report(),
                ResolverCoverageScoringCriteria(setOf(requirement)),
            )

        assertEquals(setOf(requirement), comparison.scoreChange.unmetRequirements)
    }

    @Test
    fun `suite-specific obligations count only when selected`() {
        val criteria =
            ResolverCoverageScoringCriteria(
                setOf(ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "list_object_field_error", 1))
            )
        val candidate =
            measurement(
                listOf(
                    row("E1", "list-error", OBJECT_FIELD_ONLY).copy(
                        locus = ResolverLocus.LIST_ELEMENT_OBJECT,
                        outputKind = OutputKind.ERROR,
                    )
                )
            ).report()
        val comparison = emptyReport().compareCandidate(candidate, criteria)

        assertEquals(1, comparison.candidateScore.obligationTestSupport.getValue("list_object_field_error"))
    }

    @Test
    fun `scoring criteria reject unknown obligations`() {
        val criteria =
            ResolverCoverageScoringCriteria(
                setOf(ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "unknown_obligation", 1))
            )

        assertThrows(IllegalArgumentException::class.java) {
            emptyReport().compareCandidate(emptyReport(), criteria)
        }
    }

    @Test
    fun `support tradeoffs remain incomparable`() {
        val (repeated, relationship) = scoreComparisons()

        assertEquals(ResolverCoverageScoreRelation.INCOMPARABLE, relationship.candidateScore.relationTo(repeated.candidateScore))
    }

    @Test
    fun `suite set comparison accepts a smaller set that keeps required coverage`() {
        val firstSuite = suiteMeasurement("suite-a", "E1", "A")
        val secondSuite = suiteMeasurement("suite-b", "E2", "B")
        val baseline = combineCoverageMeasurements(listOf(firstSuite, secondSuite)).report()
        val candidate = combineCoverageMeasurements(listOf(firstSuite)).report()
        val criteria =
            ResolverCoverageSuiteSetCriteria(
                requiredSuiteIds = setOf("suite-a"),
                coverage = oneObligationCriteria("both_sources_on_one_occurrence"),
            )

        val comparison = baseline.compareSuiteSetCandidate(candidate, setOf("suite-a", "suite-b"), setOf("suite-a"), criteria)

        assertEquals(ResolverCoverageSuiteSetRelation.SMALLER, comparison.relation)
        assertEquals(setOf("suite-b"), comparison.removedSuiteIds)
        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), comparison.coverage.metrics.single { it.id == "both_sources_on_one_occurrence" }.candidateSupport)
        assertEquals(2, comparison.baselineSize.cases)
        assertEquals(1, comparison.candidateSize.cases)
    }

    @Test
    fun `suite set comparison keeps explicitly required suite purposes`() {
        val firstSuite = suiteMeasurement("suite-a", "E1", "A")
        val secondSuite = suiteMeasurement("suite-b", "E2", "B")
        val baseline = combineCoverageMeasurements(listOf(firstSuite, secondSuite)).report()
        val candidate = combineCoverageMeasurements(listOf(firstSuite)).report()
        val criteria =
            ResolverCoverageSuiteSetCriteria(
                requiredSuiteIds = setOf("suite-b"),
                coverage = oneObligationCriteria("both_sources_on_one_occurrence"),
            )

        val comparison = baseline.compareSuiteSetCandidate(candidate, setOf("suite-a", "suite-b"), setOf("suite-a"), criteria)

        assertEquals(ResolverCoverageSuiteSetRelation.INFEASIBLE, comparison.relation)
        assertEquals(setOf("suite-b"), comparison.missingRequiredSuiteIds)
        assertTrue(comparison.coverage.candidateScore.meetsRequirements)
    }

    @Test
    fun `suite set comparison accepts a suite that fills a missing requirement`() {
        val firstSuite = suiteMeasurement("suite-a", "E1", "A")
        val errorSuite = suiteMeasurement("suite-b", "E2", "B").let { measurement ->
            measurement.copy(rows = measurement.rows.map { it.copy(outputKind = OutputKind.ERROR) })
        }
        val baseline = combineCoverageMeasurements(listOf(firstSuite)).report()
        val candidate = combineCoverageMeasurements(listOf(firstSuite, errorSuite)).report()
        val criteria =
            ResolverCoverageSuiteSetCriteria(
                requiredSuiteIds = setOf("suite-a"),
                coverage =
                    ResolverCoverageScoringCriteria(
                        setOf(ResolverCoverageRequirement(ResolverCoverageRequirementKind.ATTRIBUTE, "output_kind:error", 1))
                    ),
            )

        val comparison = baseline.compareSuiteSetCandidate(candidate, setOf("suite-a"), setOf("suite-a", "suite-b"), criteria)

        assertEquals(ResolverCoverageSuiteSetRelation.BECOMES_FEASIBLE, comparison.relation)
        assertEquals(setOf("suite-b"), comparison.addedSuiteIds)
        assertTrue(comparison.candidateFeasible)
    }

    @Test
    fun `repeated occurrences in one test do not add distinct-test support`() {
        val oneOccurrence = row("E1", "A", ARGUMENT_AND_OBJECT_FIELD)
        val twoOccurrences = oneOccurrence.copy(occurrenceId = "B")

        val repeated =
            measurement(listOf(oneOccurrence)).report().compareCandidate(
                measurement(listOf(oneOccurrence, twoOccurrences)).report(),
                oneObligationCriteria("both_sources_on_one_occurrence"),
            )

        assertEquals(1, repeated.candidateScore.obligationTestSupport.getValue("both_sources_on_one_occurrence"))
    }

    @Test
    fun `coverage report rejects reordered metrics`() {
        val report = measurement(emptyList()).report()
        assertThrows(IllegalArgumentException::class.java) { report.copy(metrics = report.metrics.reversed()) }
    }

    @Test
    fun `coverage report rejects a metric assigned to the wrong category`() {
        val report = measurement(emptyList()).report()
        val reclassified = report.metrics.toMutableList().apply {
            this[0] = this[0].copy(evidenceKind = CoverageEvidenceKind.EXECUTION_COEXISTENCE)
        }

        assertThrows(IllegalArgumentException::class.java) { report.copy(metrics = reclassified) }
    }

    @Test
    fun `JSON report preserves version executions categories and support`() {
        val measurement = measurement(workedExampleRows())
        val report = measurement.report()
        val json = StringBuilder().also { ResolverCoverageReportJson.write(report, it) }.toString()

        assertEquals(report, ResolverCoverageReportJson.read(json.reader()))
    }

    @Test
    fun `separate occurrences do not satisfy a local source combination`() {
        val argumentOnly = row("E1", "A", ARGUMENT_ONLY)
        val objectFieldOnly = row("E1", "B", OBJECT_FIELD_ONLY)

        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), measurement(listOf(argumentOnly, objectFieldOnly)).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `both source types count together in one execution`() {
        assertEquals(CoverageSupport(4, 2, 2, 1, 1, 1), measurement(workedExampleRows()).bothSourcesWithinOneExecution())
    }

    @Test
    fun `source types in separate executions do not count together`() {
        val argumentOnly = row("E1", "A", ARGUMENT_ONLY)
        val objectFieldOnly = row("E2", "B", OBJECT_FIELD_ONLY)

        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), measurement(listOf(argumentOnly, objectFieldOnly)).bothSourcesWithinOneExecution())
    }

    @Test
    fun `a rerun counts as a separate execution`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val rerun = first.copy(runId = "second-run", occurrenceId = "B")
        assertEquals(CoverageSupport(2, 2, 1, 1, 1, 1), measurement(listOf(first, rerun)).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `same schema in two batches counts once`() {
        val firstBatch = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val sameSchemaInAnotherBatch =
            firstBatch.copy(
                batchId = "another-batch",
                executionId = "execution-2",
                occurrenceId = "B",
            )
        assertEquals(1, measurement(listOf(firstBatch, sameSchemaInAnotherBatch)).bothSourcesOnOneOccurrence().schemas)
    }

    @Test
    fun `two schemas count separately`() {
        val firstBatch = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val sameSchemaInAnotherBatch =
            firstBatch.copy(
                batchId = "another-batch",
                executionId = "execution-2",
                occurrenceId = "B",
            )
        val anotherSchema =
            sameSchemaInAnotherBatch.copy(
                schemaId = "another-schema",
                executionId = "execution-3",
                occurrenceId = "C",
            )
        assertEquals(2, measurement(listOf(firstBatch, sameSchemaInAnotherBatch, anotherSchema)).bothSourcesOnOneOccurrence().schemas)
    }

    @Test
    fun `source supplied occurrences do not count toward metric support`() {
        val invoked = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        val sourceSupplied = invoked.copy(occurrenceId = "B", resolutionRole = ResolutionRole.SOURCE_SUPPLIED)
        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(invoked, sourceSupplied)).bothSourcesOnOneOccurrence())
    }

    @Test
    fun `separate occurrences cannot combine list source and error conditions`() {
        val obligation = listWithBothSourcesAndError()
        assertEquals(CoverageSupport(0, 0, 0, 0, 0, 0), measurement(higherOrderRows()).support(obligation))
    }

    @Test
    fun `one list occurrence with both sources and an error gets support`() {
        val matching = row("execution", "D", ARGUMENT_AND_OBJECT_FIELD).copy(
            listAncestorCount = 1,
            outputKind = OutputKind.ERROR,
        )
        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(matching)).support(listWithBothSourcesAndError()))
    }

    @Test
    fun `fan out counts distinct consumers but does not imply fan in`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val edges = listOf(
            dependency(provider.execution, "P", "Source.value", "C1", "Item.first", "one"),
            dependency(provider.execution, "P", "Source.value", "C1", "Item.first", "sameConsumerAgain"),
            dependency(provider.execution, "P", "Source.value", "C2", "Item.second", "two"),
        )

        val scored = measurement(listOf(provider, firstConsumer, secondConsumer), edges)

        assertEquals(
            RelationshipSupportSummary(CoverageSupport(1, 1, 1, 1, 1, 1), ZERO_SUPPORT),
            RelationshipSupportSummary(scored.sharedProviderFanOut(), scored.multipleProviderFanIn()),
        )
    }

    @Test
    fun `fan in counts providers with matching coordinates but does not imply fan out`() {
        val firstProvider = row("E1", "P1", emptySet(), coordinate = "Source.value")
        val secondProvider = row("E1", "P2", emptySet(), coordinate = "Source.value")
        val consumer = row("E1", "C", emptySet(), coordinate = "Item.computed")
        val edges = listOf(
            dependency(firstProvider.execution, "P1", "Source.value", "C", "Item.computed", "first"),
            dependency(firstProvider.execution, "P1", "Source.value", "C", "Item.computed", "firstAgain"),
            dependency(firstProvider.execution, "P2", "Source.value", "C", "Item.computed", "second"),
        )

        val scored = measurement(listOf(firstProvider, secondProvider, consumer), edges)

        assertEquals(
            RelationshipSupportSummary(ZERO_SUPPORT, CoverageSupport(1, 1, 1, 1, 1, 1)),
            RelationshipSupportSummary(scored.sharedProviderFanOut(), scored.multipleProviderFanIn()),
        )
    }

    @Test
    fun `coexisting occurrences without dependency records support neither relationship`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val secondProvider = row("E1", "P2", emptySet(), coordinate = "Source.second")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val scored = measurement(listOf(provider, secondProvider, firstConsumer, secondConsumer))

        assertEquals(
            RelationshipSupportSummary(ZERO_SUPPORT, ZERO_SUPPORT),
            RelationshipSupportSummary(scored.sharedProviderFanOut(), scored.multipleProviderFanIn()),
        )
    }

    @Test
    fun `success and failure score only when invoked branches share an execution`() {
        val failed = row("E1", "failed", emptySet(), coordinate = "Item.failed").copy(outputKind = OutputKind.ERROR)
        val succeeded = row("E1", "succeeded", emptySet(), coordinate = "Item.succeeded")
        val isolatedFailure = row("E2", "isolatedFailure", emptySet(), coordinate = "Item.failed").copy(outputKind = OutputKind.ERROR)
        val notInvokedSuccess = succeeded.copy(
            executionId = "E2",
            queryId = "E2",
            occurrenceId = "notInvokedSuccess",
            resolutionRole = ResolutionRole.SOURCE_SUPPLIED,
        )

        assertEquals(
            CoverageSupport(2, 1, 1, 1, 1, 1),
            measurement(listOf(failed, succeeded, isolatedFailure, notInvokedSuccess))
                .successfulAndFailingBranchesWithinOneExecution(),
        )
    }

    @Test
    fun `repeated executions count separately but do not inflate test support`() {
        val firstProvider = row("E1", "P1", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val repeatedProvider = firstProvider.copy(runId = "run-2")
        val repeatedFirstConsumer = firstConsumer.copy(runId = "run-2")
        val repeatedSecondConsumer = secondConsumer.copy(runId = "run-2")
        val firstRunEdges = listOf(
            dependency(firstProvider.execution, "P1", "Source.value", "C1", "Item.first", "one"),
            dependency(firstProvider.execution, "P1", "Source.value", "C2", "Item.second", "two"),
        )
        val repeatedRunEdges = firstRunEdges.map { it.copy(execution = repeatedProvider.execution) }
        val sameTestSupport = measurement(
            listOf(firstProvider, firstConsumer, secondConsumer, repeatedProvider, repeatedFirstConsumer, repeatedSecondConsumer),
            firstRunEdges + repeatedRunEdges,
        ).sharedProviderFanOut()
        assertEquals(CoverageSupport(2, 2, 1, 1, 1, 1), sameTestSupport)
    }

    @Test
    fun `fan out support counts a second batch`() {
        val firstProvider = row("E1", "P1", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val firstRunEdges = listOf(
            dependency(firstProvider.execution, "P1", "Source.value", "C1", "Item.first", "one"),
            dependency(firstProvider.execution, "P1", "Source.value", "C2", "Item.second", "two"),
        )
        val secondBatchRows = listOf(
            firstProvider.copy(batchId = "batch-2", executionId = "E2", queryId = "E2", occurrenceId = "P2"),
            firstConsumer.copy(batchId = "batch-2", executionId = "E2", queryId = "E2", occurrenceId = "C3"),
            secondConsumer.copy(batchId = "batch-2", executionId = "E2", queryId = "E2", occurrenceId = "C4"),
        )
        val secondBatchExecution = secondBatchRows.first().execution
        val secondBatchEdges = listOf(
            dependency(secondBatchExecution, "P2", "Source.value", "C3", "Item.first", "one"),
            dependency(secondBatchExecution, "P2", "Source.value", "C4", "Item.second", "two"),
        )
        val acrossBatches = measurement(
            listOf(firstProvider, firstConsumer, secondConsumer) + secondBatchRows,
            firstRunEdges + secondBatchEdges,
        ).sharedProviderFanOut()
        assertEquals(CoverageSupport(2, 2, 2, 2, 2, 1), acrossBatches)
    }

    @Test
    fun `measurement rejects rows without a completed execution`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        assertThrows(IllegalArgumentException::class.java) { ResolverCoverageMeasurement(emptyList(), listOf(first)) }
    }

    @Test
    fun `measurement rejects duplicate occurrence IDs`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        assertThrows(IllegalArgumentException::class.java) { measurement(listOf(first, first)) }
    }

    @Test
    fun `dependency observations can include consumers absent from result rows`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val edges = listOf(
            dependency(provider.execution, "P", "Source.value", "C1", "Item.first", "one"),
            dependency(provider.execution, "P", "Source.value", "C2", "Item.second", "two"),
        )

        assertEquals(CoverageSupport(1, 1, 1, 1, 1, 1), measurement(listOf(provider), edges).sharedProviderFanOut())
    }

    @Test
    fun `measurement rejects dependency coordinates that conflict with recorded occurrences`() {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.value")
        val consumer = row("E1", "C", emptySet(), coordinate = "Item.computed")
        val conflicting = dependency(provider.execution, "P", "Source.other", "C", "Item.computed", "value")

        assertThrows(IllegalArgumentException::class.java) {
            measurement(listOf(provider, consumer), listOf(conflicting))
        }
    }

    @Test
    fun `measurement rejects duplicate execution IDs within one run`() {
        val first = row("execution", "A", ARGUMENT_AND_OBJECT_FIELD)
        assertThrows(IllegalArgumentException::class.java) {
            ResolverCoverageMeasurement(listOf(first.execution, first.execution), emptyList())
        }
    }

    @Test
    fun `measurement rejects an unsupported model version`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolverCoverageMeasurement(emptyList(), emptyList(), modelVersion = RESOLVER_COVERAGE_MODEL_VERSION + 1)
        }
    }

    @Test
    fun `CSV round trip preserves every row classification`() {
        val row = row("execution", "A", ALL_BINDING_SOURCES).copy(
            resultPath = "[{\"argument\":\"comma, quote \\\" and newline\n\"}]",
            listAncestorCount = 3,
            maxProviderPathDepth = 2,
            argumentState = ArgumentState.CONTAINS_NULL,
            declaredOutputShape = DeclaredOutputShape.LIST,
            outputKind = OutputKind.ERROR,
        )
        assertEquals(listOf(row), ResolverCoverageCsv.read(writeCsv(listOf(row)).reader()))
    }

    @Test
    fun `CSV round trip handles no rows`() {
        assertEquals(emptyList<ResolverCoverageRow>(), ResolverCoverageCsv.read(writeCsv(emptyList()).reader()))
    }

    @Test
    fun `CSV reader rejects an unexpected header`() {
        assertThrows(IllegalArgumentException::class.java) { ResolverCoverageCsv.read("wrong,header\n".reader()) }
    }

    private fun workedExampleRows(): List<ResolverCoverageRow> =
        listOf(
            row("E1", "A", ARGUMENT_ONLY),
            row("E1", "B", OBJECT_FIELD_ONLY),
            row("E2", "C", ARGUMENT_AND_OBJECT_FIELD),
            row("E2", "D", ARGUMENT_AND_OBJECT_FIELD),
            row("E3", "E", emptySet()),
        )

    private fun higherOrderRows(): List<ResolverCoverageRow> =
        listOf(
            row("E1", "A", ARGUMENT_AND_OBJECT_FIELD).copy(listAncestorCount = 1),
            row("E1", "B", ARGUMENT_AND_OBJECT_FIELD).copy(outputKind = OutputKind.ERROR),
            row("E1", "C", emptySet()).copy(listAncestorCount = 1, outputKind = OutputKind.ERROR),
        )

    private fun listWithBothSourcesAndError() =
        OccurrenceCoverageObligation("list-both-error") {
            it.listAncestorCount > 0 &&
                it.bindingSources.containsAll(ARGUMENT_AND_OBJECT_FIELD) &&
                it.outputKind == OutputKind.ERROR
        }

    private fun measurement(
        rows: List<ResolverCoverageRow>,
        dependencies: List<ResolverDependencyRow> = emptyList(),
    ): ResolverCoverageMeasurement =
        ResolverCoverageMeasurement(
            executions = rows.map { it.execution }.distinct(),
            rows = rows,
            dependencies = dependencies,
        )

    private fun suiteMeasurement(
        suiteId: String,
        executionId: String,
        occurrenceId: String,
    ): ResolverCoverageMeasurement =
        measurement(
            listOf(
                row(executionId, occurrenceId, ARGUMENT_AND_OBJECT_FIELD).copy(
                    runId = "run-$suiteId",
                    batchId = "$suiteId/batch",
                )
            )
        )

    private fun emptyReport() = measurement(emptyList()).report()

    private fun dependencyComparison(): ResolverCoverageCandidateComparison {
        val provider = row("E1", "P", emptySet(), coordinate = "Source.first")
        val secondProvider = row("E1", "P2", emptySet(), coordinate = "Source.second")
        val firstConsumer = row("E1", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E1", "C2", emptySet(), coordinate = "Item.second")
        val rows = listOf(provider, secondProvider, firstConsumer, secondConsumer)
        val relationships = listOf(
            dependency(provider.execution, "P", "Source.first", "C1", "Item.first", "first"),
            dependency(provider.execution, "P", "Source.first", "C2", "Item.second", "second"),
            dependency(provider.execution, "P2", "Source.second", "C1", "Item.first", "third"),
        )

        return measurement(rows).report().compareCandidate(measurement(rows, relationships).report(), coreScoringCriteria())
    }

    private fun comparisonWithoutDependencyEdges(): ResolverCoverageCandidateComparison =
        measurement(emptyList()).report().compareCandidate(measurement(workedExampleRows()).report(), coreScoringCriteria())

    private fun scoreComparisons(): Pair<ResolverCoverageCandidateComparison, ResolverCoverageCandidateComparison> {
        val local = row("E1", "A", ARGUMENT_AND_OBJECT_FIELD)
        val provider = row("E2", "P", emptySet(), coordinate = "Source.value")
        val firstConsumer = row("E2", "C1", emptySet(), coordinate = "Item.first")
        val secondConsumer = row("E2", "C2", emptySet(), coordinate = "Item.second")
        val baseRows = listOf(local, provider, firstConsumer, secondConsumer)
        val baseEdges =
            listOf(
                dependency(provider.execution, "P", "Source.value", "C1", "Item.first", "first"),
                dependency(provider.execution, "P", "Source.value", "C2", "Item.second", "second"),
            )
        val baseline = measurement(baseRows, baseEdges).report()
        val repeatedSupport = measurement(baseRows + row("E3", "B", ARGUMENT_AND_OBJECT_FIELD), baseEdges).report()

        val secondProvider = row("E4", "P2", emptySet(), coordinate = "Source.value2")
        val secondFirstConsumer = row("E4", "C3", emptySet(), coordinate = "Item.third")
        val secondSecondConsumer = row("E4", "C4", emptySet(), coordinate = "Item.fourth")
        val relationshipCandidate =
            measurement(
                baseRows + listOf(secondProvider, secondFirstConsumer, secondSecondConsumer),
                baseEdges + listOf(
                    dependency(secondProvider.execution, "P2", "Source.value2", "C3", "Item.third", "third"),
                    dependency(secondProvider.execution, "P2", "Source.value2", "C4", "Item.fourth", "fourth"),
                ),
            ).report()

        val criteria =
            ResolverCoverageScoringCriteria(
                setOf(
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "both_sources_on_one_occurrence", 1),
                    ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, "shared_provider_fan_out", 1),
                )
            )
        return baseline.compareCandidate(repeatedSupport, criteria) to baseline.compareCandidate(relationshipCandidate, criteria)
    }

    private fun writeCsv(rows: List<ResolverCoverageRow>): String = StringBuilder().also { ResolverCoverageCsv.write(rows, it) }.toString()

    private fun coreScoringCriteria() =
        ResolverCoverageScoringCriteria(
            RFC_271_CORE_OBLIGATION_IDS.mapTo(linkedSetOf()) {
                ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, it, 1)
            }
        )

    private fun oneObligationCriteria(id: String) =
        ResolverCoverageScoringCriteria(
            setOf(ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, id, 1))
        )

    private fun ResolverCoverageMetricComparison.summary() =
        MetricComparisonSummary(
            id = id,
            evidenceKind = evidenceKind,
            baselineSupport = baselineSupport,
            candidateSupport = candidateSupport,
            supportChange = supportChange,
            newlySupported = newlySupported,
            noLongerSupported = noLongerSupported,
        )

    private data class MetricComparisonSummary(
        val id: String,
        val evidenceKind: CoverageEvidenceKind,
        val baselineSupport: CoverageSupport,
        val candidateSupport: CoverageSupport,
        val supportChange: CoverageSupportChange,
        val newlySupported: Boolean,
        val noLongerSupported: Boolean,
    )

    private data class AttributeSupportSummary(
        val support: Map<String, CoverageSupport>,
        val includedInSignature: Boolean,
        val supportedObligationCount: Int,
        val scoredOutputKindSupport: Int,
        val candidateRejectedForMissingAttribute: Boolean,
    )

    private data class MetricAndAttributeCatalog(
        val metrics: Map<String, CoverageEvidenceKind>,
        val attributes: Set<String>,
    )

    private fun bindingSourceAttributeIds(): Set<String> {
        val sources = ResolverBindingSource.values().toList()
        return (0 until (1 shl sources.size)).mapTo(sortedSetOf()) { mask ->
            val selected = sources.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val label = if (selected.isEmpty()) "none" else selected.map { it.name.lowercase() }.sorted().joinToString("+")
            "binding_sources:$label"
        }
    }

    private data class RelationshipSupportSummary(
        val fanOut: CoverageSupport,
        val fanIn: CoverageSupport,
    )
}

private val ZERO_SUPPORT = CoverageSupport(0, 0, 0, 0, 0, 0)
private val ZERO_SUPPORT_CHANGE = CoverageSupportChange(0, 0, 0, 0, 0, 0)

private fun row(
    execution: String,
    occurrence: String,
    sources: Set<ResolverBindingSource>,
    coordinate: String = "Item.computed",
): ResolverCoverageRow =
    ResolverCoverageRow(
        runId = "run",
        executionId = execution,
        batchId = "batch",
        schemaId = "schema",
        registryId = "registry",
        queryId = execution,
        occurrenceId = occurrence,
        resultPath = "{\"root\":\"result\",\"path\":[]}",
        fieldCoordinate = coordinate,
        resolutionRole = ResolutionRole.INVOKED,
        locus = ResolverLocus.OTHER_OBJECT,
        stampClass = StampClass.VARIABLE_FREE,
        listAncestorCount = 0,
        bindingSources = sources,
        maxProviderPathDepth = null,
        argumentState = ArgumentState.NONE,
        declaredOutputShape = DeclaredOutputShape.SCALAR,
        outputKind = OutputKind.SCALAR,
    )

private fun resultPath(vararg fields: String): String = PropertyTestJson.write(mapOf("root" to "result", "path" to fields.map { mapOf("field" to it) })).trimEnd()

private val ARGUMENT_ONLY = setOf(ResolverBindingSource.ARGUMENT)
private val OBJECT_FIELD_ONLY = setOf(ResolverBindingSource.OBJECT_FIELD)
private val ARGUMENT_AND_OBJECT_FIELD = setOf(ResolverBindingSource.ARGUMENT, ResolverBindingSource.OBJECT_FIELD)
private val ALL_BINDING_SOURCES = ResolverBindingSource.entries.toSet()

private fun dependency(
    execution: CoverageExecution,
    providerId: String,
    providerCoordinate: String,
    consumerId: String,
    consumerCoordinate: String,
    variableName: String,
): ResolverDependencyRow =
    ResolverDependencyRow(
        execution = execution,
        provider = ResolverDependencyOccurrence(providerId, providerCoordinate),
        providerSource = ResolverBindingSource.OBJECT_FIELD,
        providerOutcome = ProviderCellOutcome.VALUE,
        consumer = ResolverDependencyOccurrence(consumerId, consumerCoordinate),
        variableName = variableName,
    )
