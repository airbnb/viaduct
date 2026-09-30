package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import model.operationSelectionsFrom
import model.testing.TestWorld
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext

class ParentCoverageMetricsTest : Resolver26DispatcherResource {
    @Test
    fun `classifies variable sources and diagonal parent demand across a resolver boundary`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      grand: Grand!
                        @resolver(result: {grandValue: 11})
                    }

                    type Grand {
                      grandValue: Int!
                      parentNode: Parent!
                        @resolver(result: {fromSource: 7})
                    }

                    type Parent {
                      parent: Grand @parent
                      fromSource: Int!
                      child: Child!
                        @resolver(result: {})
                      bridge(seed: Int!): Int!
                        @resolver(
                          of: "fromSource consume(seed: ${'$'}seed, source: ${'$'}fromSource) parent { grandValue }"
                          pathVars: [{name: "fromSource", path: ["fromSource"]}]
                          result: "sum(consume, parent.grandValue)"
                        )
                      consume(seed: Int!, source: Int!): Int!
                        @resolver(result: "sum(${'$'}seed, ${'$'}source)")
                    }

                    type Child {
                      parent: Parent @parent
                      result: Int!
                        @resolver(
                          of: "parent { bridge(seed: 2) }"
                          result: "sum(parent.bridge)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val coverage = mutableListOf<ParentSelectionSetCoverage>()

        val recordingObserver = object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                coverage += ParentCoverageAnalyzer(world).analyze(observation)
            }
        }
        val operation =
            SharedOperationContext.create(
                world = world,
                resolverObserver = recordingObserver,
            )
        operation.resolveWithTestDispatcher(
            testWorld.schemas.operationSelectionsFrom(
                "query { grand { parentNode { child { result } } } }",
            ),
        )

        val childParent =
            coverage.single { parent ->
                parent.field.containingDef.name == "Child" && parent.field.name == "parent"
            }
        val bridge =
            childParent.selectedResolvers.single { selected ->
                selected.field.containingDef.name == "Parent" && selected.field.name == "bridge"
            }
        assertEquals(1, bridge.selectionDepthBelowParent)
        assertEquals(
            setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.OBJECT_FIELD),
            bridge.requiredInputVariableSources,
        )
        assertEquals(
            listOf(
                ParentResolverVariableArgumentCoverage(
                    fragment = ParentResolverInputFragment.OBJECT,
                    selectionDepth = 1,
                    variableSources =
                        setOf(
                            ParentVariableSource.ARGUMENT,
                            ParentVariableSource.OBJECT_FIELD,
                        ),
                ),
            ),
            bridge.variableArgumentSelections,
        )
        assertEquals(1, bridge.diagonalParentDepth)
        assertTrue(coverage.all { parent -> parent.argumentVariables.isEmpty() })

        val snapshot = parentFocusedCoverageSnapshot(world, coverage)
        assertContains(snapshot.parentDepths, 1)
        assertContains(snapshot.producerShapes, ParentProducerShape.SINGULAR)
        assertContains(snapshot.targetKinds, ParentTargetKind.CONCRETE)
        assertContains(snapshot.resolverPlacements, ParentResolverPlacement.DIRECT)
        assertContains(
            snapshot.mixedVariableSourcePairs,
            setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.OBJECT_FIELD),
        )
        assertContains(
            snapshot.diagonalVariableFragments,
            ParentVariableFragment(
                ParentVariableSource.OBJECT_FIELD,
                ParentResolverInputFragment.OBJECT,
            ),
        )
    }

    @Test
    fun `reports all nine criteria for each parent-focused slice`() {
        val sources =
            setOf(
                ParentVariableSource.ARGUMENT,
                ParentVariableSource.OBJECT_FIELD,
                ParentVariableSource.QUERY_FIELD,
            )
        val fragments = ParentResolverInputFragment.entries.toSet()
        val complete =
            ParentFocusedCoverageSnapshot(
                parentDepths = setOf(1, 2, 3),
                producerShapes = ParentProducerShape.entries.toSet(),
                targetKinds = ParentTargetKind.entries.toSet(),
                resolverPlacements = ParentResolverPlacement.entries.toSet(),
                variableSources = sources,
                mixedVariableSourcePairs =
                    setOf(
                        setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.OBJECT_FIELD),
                        setOf(ParentVariableSource.ARGUMENT, ParentVariableSource.QUERY_FIELD),
                        setOf(ParentVariableSource.OBJECT_FIELD, ParentVariableSource.QUERY_FIELD),
                    ),
                inputFragments = fragments,
                argumentSelectionDepths = ParentArgumentSelectionDepth.entries.toSet(),
                diagonalDepths = setOf(1, 2),
                diagonalVariableFragments =
                    sources.flatMapTo(linkedSetOf()) { source ->
                        fragments.map { fragment -> ParentVariableFragment(source, fragment) }
                    },
                sometimesPassiveParentDemandOccurrences = 3,
                sometimesPassiveParentDemandDepths = setOf(1, 2, 3),
            )
        val report = ParentFocusedCoverageReport(schemaCount = 40, sliceCount = 4)
        repeat(40) { schemaOffset ->
            repeat(25) { report.record(schemaOffset + 1, complete) }
        }

        val rendered = report.render()

        (1..4).forEach { run -> assertContains(rendered, "RUN $run/4: HIT") }
        (1..9).forEach { criterion -> assertContains(rendered, "  $criterion.") }
        assertContains(rendered, "COMBINED CRITERION COVERAGE:")
        ParentFocusedCoverageSnapshot().criteria().forEach { criterion ->
            assertContains(
                rendered,
                "${criterion.number}. ${criterion.name}: slices=4/4, " +
                    "instances=1000/1000 cases, bySlice=[250/250, 250/250, 250/250, 250/250]",
            )
        }
        assertContains(rendered, "FOUR-RUN RESULT: HIT (4/4 runs hit all nine criteria)")
        assertContains(rendered, "COMBINED RESULT: HIT")
        report.requireCombinedHit()
    }

    @Test
    fun `coverage misses are reported and rejected`() {
        val report = ParentFocusedCoverageReport(schemaCount = 4, sliceCount = 4)
        repeat(4) { schemaOffset -> report.record(schemaOffset + 1, ParentFocusedCoverageSnapshot()) }

        val rendered = report.render()

        assertContains(rendered, "RUN 1/4: MISS")
        assertContains(rendered, "1. Parent topology: MISS")
        assertContains(rendered, "8. Variable-bearing diagonals: MISS")
        assertContains(rendered, "9. Sometimes-passive parent demand: MISS")
        assertContains(rendered, "COMBINED CRITERION COVERAGE:")
        ParentFocusedCoverageSnapshot().criteria().forEach { criterion ->
            assertContains(
                rendered,
                "${criterion.number}. ${criterion.name}: slices=0/4, " +
                    "instances=0/4 cases, bySlice=[0/1, 0/1, 0/1, 0/1]",
            )
        }
        assertContains(rendered, "FOUR-RUN RESULT: MISS (0/4 runs hit all nine criteria)")
        assertContains(rendered, "COMBINED RESULT: MISS")
        val failure = assertFailsWith<IllegalStateException> { report.requireCombinedHit() }
        assertContains(failure.message.orEmpty(), "1. Parent topology")
        assertContains(failure.message.orEmpty(), "9. Sometimes-passive parent demand")
    }

    @Test
    fun `counts criterion instances once per recorded generated case`() {
        val report = ParentFocusedCoverageReport(schemaCount = 4, sliceCount = 2)
        report.record(
            schemaIndex = 1,
            snapshot =
                ParentFocusedCoverageSnapshot(
                    parentDepths = setOf(1, 2),
                    resolverPlacements = setOf(ParentResolverPlacement.DIRECT),
                    variableSources = setOf(ParentVariableSource.ARGUMENT),
                ),
        )
        report.record(schemaIndex = 1, snapshot = ParentFocusedCoverageSnapshot())
        report.record(
            schemaIndex = 3,
            snapshot =
                ParentFocusedCoverageSnapshot(
                    parentDepths = setOf(1),
                    diagonalDepths = setOf(1),
                    diagonalVariableFragments =
                        setOf(
                            ParentVariableFragment(
                                ParentVariableSource.ARGUMENT,
                                ParentResolverInputFragment.OBJECT,
                            ),
                        ),
                ),
        )

        val rendered = report.render()

        assertContains(
            rendered,
            "1. Parent topology: slices=0/2, instances=2/3 cases, bySlice=[1/2, 1/1]",
        )
        assertContains(
            rendered,
            "2. Resolver placement: slices=0/2, instances=1/3 cases, bySlice=[1/2, 0/1]",
        )
        assertContains(
            rendered,
            "8. Variable-bearing diagonals: slices=0/2, " +
                "instances=1/3 cases, bySlice=[0/2, 1/1]",
        )
    }
}
