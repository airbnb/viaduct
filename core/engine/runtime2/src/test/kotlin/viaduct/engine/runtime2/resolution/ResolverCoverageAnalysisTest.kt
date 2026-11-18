package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.coverage.ArgumentState
import viaduct.engine.runtime2.coverage.CoverageCaseEditSummary
import viaduct.engine.runtime2.coverage.CoverageCaseStatus
import viaduct.engine.runtime2.coverage.CoverageEvidenceLocation
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageTestId
import viaduct.engine.runtime2.coverage.DeclaredOutputShape
import viaduct.engine.runtime2.coverage.OutputKind
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverBindingSource
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageObligationEvidence
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.ResolverCoverageSignature
import viaduct.engine.runtime2.coverage.ResolverDependencyOccurrence
import viaduct.engine.runtime2.coverage.ResolverDependencyRow
import viaduct.engine.runtime2.coverage.ResolverLocus
import viaduct.engine.runtime2.coverage.ResolverMetricsCsv
import viaduct.engine.runtime2.coverage.StampClass
import viaduct.engine.runtime2.coverage.analyze
import viaduct.engine.runtime2.coverage.assessTestCaseRemoval
import viaduct.engine.runtime2.coverage.compareCandidate
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome

class ResolverCoverageAnalysisTest {
    @Test
    fun `scope summaries count test cases and covered obligations`() {
        val localOne = localCase(test("schema-a", "query-a"), "local-one")
        val localTwo = localCase(test("schema-a", "query-b"), "local-two")
        val fanOutOne = fanOutCase(test("schema-b", "query-c"), "fan-out-one")
        val fanOutTwo = fanOutCase(test("schema-b", "query-d"), "fan-out-two")

        val suite = measurement(localOne, localTwo, fanOutOne, fanOutTwo).analyze().suite

        assertEquals(4, suite.testCases)
        assertEquals(
            setOf("both_sources_on_one_occurrence", "both_sources_within_one_execution", "shared_provider_fan_out"),
            suite.coveredObligations,
        )
    }

    @Test
    fun `case signature records both source combinations without counting occurrences as cases`() {
        val fixture = localCase(test("schema-a", "query-a"), "same-test", occurrenceIds = listOf("one", "two"))
        val analysis = measurement(fixture).analyze()
        val signature = analysis.signatures.single()

        assertEquals(
            CaseSignatureSummary(
                signature =
                    ResolverCoverageSignature(
                        test = test("schema-a", "query-a"),
                        obligations = setOf("both_sources_on_one_occurrence", "both_sources_within_one_execution"),
                        attributes =
                            setOf(
                                "resolution_role:invoked",
                                "resolver_locus:other_object",
                                "stamp_class:variable_free",
                                "provider_path_depth:none",
                                "binding_sources:argument+object_field",
                                "argument_state:none",
                                "declared_output_shape:scalar",
                                "output_kind:scalar",
                                "list_ancestor_count:0",
                                "object_fragment_depth:0",
                            ),
                        evidence =
                            listOf(
                                ResolverCoverageObligationEvidence(
                                    "both_sources_on_one_occurrence",
                                    setOf(
                                        CoverageEvidenceLocation("Item.computed", stablePath("Item.computed", "one")),
                                        CoverageEvidenceLocation("Item.computed", stablePath("Item.computed", "two")),
                                    ),
                                ),
                                ResolverCoverageObligationEvidence(
                                    "both_sources_within_one_execution",
                                    setOf(
                                        CoverageEvidenceLocation("Item.computed", stablePath("Item.computed", "one")),
                                        CoverageEvidenceLocation("Item.computed", stablePath("Item.computed", "two")),
                                    ),
                                ),
                            ).sortedBy { it.obligationId },
                    ),
                testCases = 1,
            ),
            CaseSignatureSummary(
                signature = signature,
                testCases = analysis.suite.testCases,
            ),
        )
    }

    @Test
    fun `replaying the same case with new execution and occurrence IDs keeps evidence unchanged`() {
        val test = test("schema-a", "query-a")
        val originalExecution = CoverageExecution("run-before", "execution-before", test)
        val replayExecution = CoverageExecution("run-after", "execution-after", test)
        val originalRow = row(test, originalExecution.executionId, "occurrence-before", ARGUMENT_AND_OBJECT_FIELD)
            .copy(runId = originalExecution.runId)
        val replayRow = originalRow.copy(
            runId = replayExecution.runId,
            executionId = replayExecution.executionId,
            occurrenceId = "occurrence-after",
        )
        val before = ResolverCoverageMeasurement(listOf(originalExecution), listOf(originalRow)).analyze()
        val after = ResolverCoverageMeasurement(listOf(replayExecution), listOf(replayRow)).analyze()

        assertEquals(CoverageCaseStatus.UNCHANGED, before.compareCandidate(after).cases.single().status)
    }

    @Test
    fun `case signatures keep resolver attributes separate from obligations`() {
        val errorTest = test("schema-a", "query-error")
        val treeTest = test("schema-b", "query-tree")
        val treeExecution = "tree-execution"
        val ancestor =
            row(treeTest, treeExecution, "ancestor", emptySet(), "Item.parent").copy(
                resultPath = "{\"root\":\"result\",\"path\":[{\"field\":\"Item.parent\"}]}",
            )
        val descendant =
            row(treeTest, treeExecution, "descendant", emptySet(), "Parent.child").copy(
                resultPath = "{\"root\":\"result\",\"path\":[{\"field\":\"Item.parent\"},{\"field\":\"Parent.child\"}]}",
            )
        val listError =
            row(errorTest, "error-execution", "list-error", setOf(ResolverBindingSource.OBJECT_FIELD), "Item.bad", OutputKind.ERROR)
                .copy(locus = ResolverLocus.LIST_ELEMENT_OBJECT)
        val tree =
            Fixture(
                treeTest,
                listOf(ancestor, descendant),
                listOf(Edge("ancestor", "Item.parent", "descendant", "Parent.child")),
            )

        val signatures = measurement(Fixture(errorTest, listOf(listError)), tree).analyze().signatures

        assertEquals(
            CaseSignalsByQuery(
                obligations =
                    mapOf(
                        "query-error" to setOf("list_object_field_error"),
                        "query-tree" to setOf("resolver_output_contains_descendant"),
                    ),
                attributes =
                    mapOf(
                        "query-error" to setOf("output_kind:error", "resolver_locus:list_element_object"),
                        "query-tree" to setOf("output_kind:scalar", "resolver_locus:other_object"),
                    ),
            ),
            CaseSignalsByQuery(
                obligations = signatures.associate { it.test.queryId to it.obligations },
                attributes =
                    signatures.associate {
                        it.test.queryId to it.attributes.intersect(
                            setOf("output_kind:error", "resolver_locus:list_element_object", "output_kind:scalar", "resolver_locus:other_object")
                        )
                    },
            ),
        )
    }

    @Test
    fun `empty executions remain visible as empty signatures`() {
        val empty = Fixture(test("schema-a", "query-a"), emptyList())
        val suite = measurement(empty).analyze().suite

        assertEquals(1, suite.testCases)
        assertEquals(emptySet<String>(), suite.coveredObligations)
    }

    @Test
    fun `analysis groups cases by registry and schema`() {
        val analysis = richAnalysis()

        assertEquals(
            ScopeGroups(
                registries = setOf("registry-schema-a", "registry-schema-b"),
                schemas = setOf("schema-a", "schema-b"),
            ),
            ScopeGroups(
                registries = analysis.registries.mapNotNull { it.registryId }.toSet(),
                schemas = analysis.schemas.mapNotNull { it.schemaId }.toSet(),
            ),
        )
    }

    @Test
    fun `similarity analysis keeps each case signature and its evidence coordinates`() {
        val analysis = richAnalysis()

        assertEquals(
            SignatureEvidenceSummary(
                obligationsByQuery =
                    mapOf(
                        "query-a" to setOf(
                            "both_sources_on_one_occurrence",
                            "both_sources_within_one_execution",
                            "multiple_provider_fan_in",
                            "shared_provider_fan_out",
                            "successful_and_failing_branches_within_one_execution",
                        ),
                        "query-b" to setOf(
                            "both_sources_on_one_occurrence",
                            "both_sources_within_one_execution",
                            "multiple_provider_fan_in",
                            "successful_and_failing_branches_within_one_execution",
                        ),
                    ),
                allCoordinatesPresent = true,
            ),
            SignatureEvidenceSummary(
                obligationsByQuery = analysis.signatures.associate { it.test.queryId to it.obligations },
                allCoordinatesPresent = analysis.signatures.flatMap { it.evidence }.flatMap { it.locations }.all { it.fieldCoordinate.isNotBlank() },
            ),
        )
    }

    @Test
    fun `resolver D metrics are computed per batch from resolver fingerprints`() {
        val rows = mutableListOf<ResolverCoverageRow>()
        val executions = mutableListOf<CoverageExecution>()

        fun observe(
            batchId: String,
            queryId: String,
            executionId: String,
            occurrenceId: String,
            resolverName: String,
            depth: Int,
        ) {
            val test = CoverageTestId(batchId, "schema", "registry", queryId)
            executions += CoverageExecution("run", executionId, test)
            rows += row(test, executionId, occurrenceId, emptySet()).copy(
                resolverName = resolverName,
                fieldCoordinate = resolverName,
                listAncestorCount = depth,
            )
        }

        observe("suite/b001", "q1", "e1", "a1", "Item.a", 0)
        observe("suite/b001", "q2", "e2", "a2", "Item.a", 1)
        observe("suite/b001", "q3", "e3", "b1", "Item.b", 0)
        observe("suite/b001", "q4", "e4", "b2", "Item.b", 1)
        observe("suite/b001", "q5", "e5", "c1", "Item.c", 0)
        executions += CoverageExecution("run", "empty-batch", CoverageTestId("suite/b002", "schema-empty", "registry", "q0"))

        val analysis = ResolverCoverageMeasurement(executions, rows).analyze()
        val scored = analysis.batchDiversity.single { it.batchId == "suite/b001" }
        val empty = analysis.batchDiversity.single { it.batchId == "suite/b002" }

        assertEquals(3, scored.resolverDefinitions)
        assertEquals(2, scored.d0)
        assertEquals(kotlin.math.exp(-(2.0 / 3 * kotlin.math.ln(2.0 / 3) + 1.0 / 3 * kotlin.math.ln(1.0 / 3))), scored.d1, 1e-12)
        assertEquals(1.8, scored.d2, 1e-12)
        assertEquals(0, empty.resolverDefinitions)
        assertEquals(0, empty.d0)
        assertEquals(0.0, empty.d1)
        assertEquals(0.0, empty.d2)
    }

    @Test
    fun `resolver Jaccard uses indexed candidates across batches and reports exact close pairs`() {
        val executions = mutableListOf<CoverageExecution>()
        val rows = mutableListOf<ResolverCoverageRow>()

        fun addResolver(
            batchId: String,
            registryId: String,
            resolverName: String,
            depths: IntRange,
        ) {
            depths.forEachIndexed { index, depth ->
                val test = CoverageTestId(batchId, "schema-$batchId", registryId, "q$index")
                val executionId = "e-$batchId-$resolverName-$index"
                executions += CoverageExecution("run", executionId, test)
                rows += row(test, executionId, "occ-$index", emptySet()).copy(
                    resolverName = resolverName,
                    fieldCoordinate = resolverName,
                    listAncestorCount = depth,
                )
            }
        }

        addResolver("suite/b001", "reg-a", "Item.left", 0..3)
        addResolver("suite/b002", "reg-b", "Item.right", 0..4)
        addResolver("suite/b003", "reg-c", "Item.other", 99..99)
        addResolver("suite/b004", "reg-d", "Item.left", 0..3)

        val analysis = ResolverCoverageMeasurement(executions, rows).analyze()
        val pair = analysis.highlySimilarResolvers.single()

        assertEquals(0.8, pair.jaccardSimilarity, 1e-12)
        assertEquals("Item.left", pair.left.resolverName)
        assertEquals(setOf("reg-a", "reg-d"), pair.left.supportingRegistries)
        assertEquals(setOf("suite/b001", "suite/b004"), pair.left.supportingBatches)
        assertEquals("Item.right", pair.right.resolverName)
        assertEquals(setOf("reg-b"), pair.right.supportingRegistries)
        assertEquals(setOf("suite/b002"), pair.right.supportingBatches)
        assertEquals(1, analysis.resolverSimilarityComparisons)
        assertEquals(4, analysis.resolverFingerprints.size)
        assertEquals(3, analysis.suiteResolverFingerprints.size)
        assertEquals(false, analysis.highlySimilarResolvers.any { it.left.resolverName == it.right.resolverName })

        val csv = StringBuilder().also { ResolverMetricsCsv.write(analysis, it) }.toString()
        assertEquals(4, csv.lineSequence().count { it.contains(",D0,") })
        assertEquals(4, csv.lineSequence().count { it.contains(",D1,") })
        assertEquals(4, csv.lineSequence().count { it.contains(",D2,") })
        assertEquals(1, csv.lineSequence().count { it.contains(",Jaccard,") })
    }

    @Test
    fun `resolver Jaccard prefix index skips pairs sharing only a common signature`() {
        val executions = mutableListOf<CoverageExecution>()
        val rows = mutableListOf<ResolverCoverageRow>()

        repeat(32) { index ->
            val batchId = "suite/b${index.toString().padStart(3, '0')}"
            val registryId = "registry-$index"
            val commonTest = CoverageTestId(batchId, "schema-$index", registryId, "common")
            val uniqueTest = CoverageTestId(batchId, "schema-$index", registryId, "unique")
            val commonExecution = "common-$index"
            val uniqueExecution = "unique-$index"
            executions += CoverageExecution("run", commonExecution, commonTest)
            executions += CoverageExecution("run", uniqueExecution, uniqueTest)
            rows += row(commonTest, commonExecution, "common-occurrence", emptySet()).copy(
                resolverName = "Item.field$index",
                fieldCoordinate = "Item.field$index",
                listAncestorCount = 0,
            )
            rows += row(uniqueTest, uniqueExecution, "unique-occurrence", emptySet()).copy(
                resolverName = "Item.field$index",
                fieldCoordinate = "Item.field$index",
                listAncestorCount = index + 1,
            )
        }

        val analysis = ResolverCoverageMeasurement(executions, rows).analyze()

        assertEquals(0, analysis.resolverSimilarityComparisons)
        assertEquals(0, analysis.highlySimilarResolvers.size)
    }

    @Test
    fun `test removal assessment rejects removing unique taxonomy or interaction coverage`() {
        val duplicateOne = localCase(test("schema-a", "query-a"), "one")
        val duplicateTwo = localCase(test("schema-a", "query-b"), "two")
        val analysis = measurement(duplicateOne, duplicateTwo, fanOutCase(test("schema-b", "query-c"), "fan-out")).analyze()

        assertEquals(true, analysis.assessTestCaseRemoval(duplicateOne.test).coveragePreserved)
        val uniqueSupport = analysis.assessTestCaseRemoval(test("schema-b", "query-c"))
        assertEquals(false, uniqueSupport.coveragePreserved)
        assertEquals(setOf("shared_provider_fan_out"), uniqueSupport.noLongerCoveredObligations)
    }

    @Test
    fun `candidate comparison reports case and schema obligation gains and losses`() {
        val changedTest = test("schema-a", "query-a")
        val baseline = measurement(
            localCase(changedTest, "changed"),
            fanOutCase(test("schema-b", "query-b"), "another-provider"),
        ).analyze()
        val candidate = measurement(
            fanOutCase(changedTest, "changed"),
            localCase(test("schema-c", "query-c"), "another-local"),
        ).analyze()

        val comparison = baseline.compareCandidate(candidate)
        val changed = comparison.cases.single { it.test == changedTest }

        assertEquals(
            CaseComparisonSummary(
                caseId = changedTest,
                status = CoverageCaseStatus.CHANGED,
                baselineObligations = setOf("both_sources_on_one_occurrence", "both_sources_within_one_execution"),
                candidateObligations = setOf("shared_provider_fan_out"),
                baselineAttributes = setOf("binding_sources:argument+object_field"),
                candidateAttributes = setOf("binding_sources:none"),
                newlyCovered = setOf("shared_provider_fan_out"),
                noLongerCovered = setOf("both_sources_on_one_occurrence", "both_sources_within_one_execution"),
                candidateEvidenceCoordinates =
                    setOf("Source.value", "Item.first", "Item.second"),
                editSummary = CoverageCaseEditSummary(1, 1, 1, 0),
            ),
            CaseComparisonSummary(
                caseId = changed.test,
                status = changed.status,
                baselineObligations = changed.baselineObligations,
                candidateObligations = changed.candidateObligations,
                baselineAttributes =
                    (changed.baselineAttributes - changed.candidateAttributes)
                        .filterTo(sortedSetOf()) { it.startsWith("binding_sources:") },
                candidateAttributes =
                    (changed.candidateAttributes - changed.baselineAttributes)
                        .filterTo(sortedSetOf()) { it.startsWith("binding_sources:") },
                newlyCovered = changed.newlyCovered,
                noLongerCovered = changed.noLongerCovered,
                candidateEvidenceCoordinates =
                    changed.candidateEvidence.flatMapTo(sortedSetOf()) { evidence ->
                        evidence.locations.map { it.fieldCoordinate }
                    },
                editSummary = comparison.caseEdits,
            ),
        )
    }

    @Test
    fun `removing the last supporting schema reports lost obligations`() {
        val baseline = measurement(fanOutCase(test("schema-a", "query-a"), "only-support")).analyze()
        val comparison = baseline.compareCandidate(measurement().analyze())
        val schema = comparison.schemas.single { it.schemaId == "schema-a" }

        assertEquals(
            CoverageChangeSummary(
                schema = setOf("shared_provider_fan_out"),
                suite = setOf("shared_provider_fan_out"),
                case = setOf("shared_provider_fan_out"),
            ),
            CoverageChangeSummary(
                schema = schema.noLongerCovered,
                suite = comparison.suite.noLongerCovered,
                case = comparison.cases.single().noLongerCovered,
            ),
        )
    }

    @Test
    fun `adding a schema reports obligations not already supported`() {
        val candidate = measurement(fanOutCase(test("schema-a", "query-a"), "new-support")).analyze()
        val comparison = measurement().analyze().compareCandidate(candidate)
        val schema = comparison.schemas.single { it.schemaId == "schema-a" }

        assertEquals(
            CoverageChangeSummary(
                schema = setOf("shared_provider_fan_out"),
                suite = setOf("shared_provider_fan_out"),
                case = setOf("shared_provider_fan_out"),
            ),
            CoverageChangeSummary(
                schema = schema.newlyCovered,
                suite = comparison.suite.newlyCovered,
                case = comparison.cases.single().newlyCovered,
            ),
        )
    }

    @Test
    fun `versioned coverage report and candidate comparison include analysis`() {
        val baseline = measurement(localCase(test("schema-a", "query-a"), "baseline")).report()
        val candidate = measurement(fanOutCase(test("schema-b", "query-b"), "candidate")).report()

        assertEquals(
            baseline.analysis.compareCandidate(candidate.analysis),
            baseline.compareCandidate(candidate, analysisTestCriteria()).analysis,
        )
    }

    private fun richAnalysis() =
        measurement(
            richCase(test("schema-a", "query-a"), "full", includeFanOut = true),
            richCase(test("schema-b", "query-b"), "almost-full", includeFanOut = false),
        ).analyze()

    private fun analysisTestCriteria() =
        ResolverCoverageScoringCriteria(
            setOf(
                ResolverCoverageRequirement(
                    ResolverCoverageRequirementKind.OBLIGATION,
                    "both_sources_on_one_occurrence",
                    1,
                )
            )
        )

    private fun localCase(
        test: CoverageTestId,
        executionId: String,
        occurrenceIds: List<String> = listOf("local")
    ): Fixture =
        Fixture(
            test,
            occurrenceIds.map { row(test, executionId, it, ARGUMENT_AND_OBJECT_FIELD) },
        )

    private fun fanOutCase(
        test: CoverageTestId,
        executionId: String
    ): Fixture =
        Fixture(
            test,
            listOf(
                row(test, executionId, "provider", emptySet(), "Source.value"),
                row(test, executionId, "first-consumer", emptySet(), "Item.first"),
                row(test, executionId, "second-consumer", emptySet(), "Item.second"),
            ),
            listOf(
                Edge("provider", "Source.value", "first-consumer", "Item.first"),
                Edge("provider", "Source.value", "second-consumer", "Item.second"),
            ),
        )

    private fun richCase(
        test: CoverageTestId,
        executionId: String,
        includeFanOut: Boolean
    ): Fixture =
        Fixture(
            test,
            listOf(
                row(test, executionId, "provider-one", emptySet(), "Source.first"),
                row(test, executionId, "provider-two", emptySet(), "Source.second"),
                row(test, executionId, "error-consumer", ARGUMENT_AND_OBJECT_FIELD, "Item.error", OutputKind.ERROR),
                row(test, executionId, "successful-consumer", emptySet(), "Item.success"),
            ),
            buildList {
                add(Edge("provider-one", "Source.first", "error-consumer", "Item.error"))
                add(Edge("provider-two", "Source.second", "error-consumer", "Item.error"))
                if (includeFanOut) add(Edge("provider-one", "Source.first", "successful-consumer", "Item.success"))
            },
        )

    private fun measurement(vararg fixtures: Fixture): ResolverCoverageMeasurement {
        val executions = fixtures.mapIndexed { index, fixture -> CoverageExecution("run", "execution-$index", fixture.test) }
        val rows = fixtures.flatMapIndexed { index, fixture -> fixture.rows.map { it.copy(executionId = "execution-$index") } }
        val dependencies = fixtures.flatMapIndexed { index, fixture ->
            val execution = executions[index]
            fixture.edges.map { edge ->
                ResolverDependencyRow(
                    execution = execution,
                    provider = ResolverDependencyOccurrence(edge.providerId, edge.providerCoordinate),
                    providerSource = ResolverBindingSource.OBJECT_FIELD,
                    providerOutcome = ProviderCellOutcome.VALUE,
                    consumer = ResolverDependencyOccurrence(edge.consumerId, edge.consumerCoordinate),
                    variableName = "source-${edge.consumerId}",
                )
            }
        }
        return ResolverCoverageMeasurement(executions, rows, dependencies = dependencies)
    }

    private fun test(
        schemaId: String,
        queryId: String
    ): CoverageTestId = CoverageTestId("batch", schemaId, "registry-$schemaId", queryId)

    private fun row(
        test: CoverageTestId,
        executionId: String,
        occurrenceId: String,
        sources: Set<ResolverBindingSource>,
        coordinate: String = "Item.computed",
        outputKind: OutputKind = OutputKind.SCALAR,
    ): ResolverCoverageRow =
        ResolverCoverageRow(
            runId = "run",
            executionId = executionId,
            batchId = test.batchId,
            schemaId = test.schemaId,
            registryId = test.registryId,
            queryId = test.queryId,
            occurrenceId = occurrenceId,
            resultPath = stablePath(coordinate, occurrenceId),
            fieldCoordinate = coordinate,
            resolutionRole = ResolutionRole.INVOKED,
            locus = ResolverLocus.OTHER_OBJECT,
            stampClass = StampClass.VARIABLE_FREE,
            listAncestorCount = 0,
            bindingSources = sources,
            maxProviderPathDepth = null,
            argumentState = ArgumentState.NONE,
            declaredOutputShape = DeclaredOutputShape.SCALAR,
            outputKind = outputKind,
        )

    private data class Fixture(
        val test: CoverageTestId,
        val rows: List<ResolverCoverageRow>,
        val edges: List<Edge> = emptyList(),
    )

    private data class Edge(
        val providerId: String,
        val providerCoordinate: String,
        val consumerId: String,
        val consumerCoordinate: String,
    )

    private data class CaseSignatureSummary(
        val signature: ResolverCoverageSignature,
        val testCases: Int,
    )

    private data class ScopeGroups(
        val registries: Set<String>,
        val schemas: Set<String>,
    )

    private data class SignatureEvidenceSummary(
        val obligationsByQuery: Map<String, Set<String>>,
        val allCoordinatesPresent: Boolean,
    )

    private data class CaseSignalsByQuery(
        val obligations: Map<String, Set<String>>,
        val attributes: Map<String, Set<String>>,
    )

    private data class CaseComparisonSummary(
        val caseId: CoverageTestId,
        val status: CoverageCaseStatus,
        val baselineObligations: Set<String>,
        val candidateObligations: Set<String>,
        val baselineAttributes: Set<String>,
        val candidateAttributes: Set<String>,
        val newlyCovered: Set<String>,
        val noLongerCovered: Set<String>,
        val candidateEvidenceCoordinates: Set<String>,
        val editSummary: CoverageCaseEditSummary,
    )

    private data class CoverageChangeSummary(
        val schema: Set<String>,
        val suite: Set<String>,
        val case: Set<String>,
    )

    private companion object {
        val ARGUMENT_AND_OBJECT_FIELD = setOf(ResolverBindingSource.ARGUMENT, ResolverBindingSource.OBJECT_FIELD)
    }

    private fun stablePath(
        coordinate: String,
        slot: String,
    ): String =
        PropertyTestJson.write(
            mapOf(
                "root" to "result",
                "path" to listOf(mapOf("field" to coordinate, "slot" to slot)),
            ),
        )
}
