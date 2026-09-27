package semantics.contract

import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import model.requireObjectField
import model.selectionForestOf
import org.junit.jupiter.api.Test
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.GeneratedFieldCheckerMode
import semantics.arbitrary.ImplementationArgumentDefaultWeight
import semantics.arbitrary.InputListTypeWeight
import semantics.arbitrary.InputObjectCount
import semantics.arbitrary.InputObjectTypeWeight
import semantics.arbitrary.MaxInputTypeDepth
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverApplicationRecord
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentArgumentFieldWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentNestedPathWeight
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromProviderVariablesEnabled
import semantics.arbitrary.ResolverFromFieldProviderArgumentVariableWeight
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverNestedProviderPathWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.RootFieldReferencesEnabled
import semantics.arbitrary.RootFieldReferenceWeight
import semantics.arbitrary.ResolverTestCase
import semantics.arbitrary.ResolverTestRun
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SelectiveNodeResolversEnabled
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.resolutionDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import semantics.correctresolution.CorrectnessResolverObserver

/**
 * Generated contract for user-declared resolvers with empty object fragments and no variables.
 */
interface EmptyObjectFragmentGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated empty object fragment worlds resolve correctly`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)

            checkGeneratedProfile("empty-object-fragment", config) { testWorld, testCase ->
                assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
                assertTrue(testCase.registry.objectFragmentSources.values.all(String::isEmpty))
                assertEquals(0, testCase.registry.features.variableCount)
                observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
            }
        }
}

/** Generated root-field-reference coverage within the base resolver capability tier. */
interface RootFieldReferenceGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated root-field-reference worlds resolve correctly`(): Unit =
        runBlocking {
            var generatedReferences = 0
            var activatedReferences = 0
            val assertions =
                generatedCaseAssertions.filterNot { assertion ->
                    assertion === GeneratedCaseAssertions.exactOrdinaryApplicationCounts
                }
            val config =
                Config.default +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverQueryFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false) +
                    (RootFieldReferencesEnabled to true) +
                    (RootFieldReferenceWeight to 0.25)

            val run =
                checkGeneratedProfile("root-field-reference", config) { testWorld, testCase ->
                    generatedReferences +=
                        testCase.registry.features.generatedRootFieldReferenceCount
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(
                            testWorld = testWorld,
                            testCase = testCase,
                            assertions = assertions,
                        )
                    activatedReferences +=
                        (observation.ordinary.operation.resolverObserver as CorrectnessResolverObserver)
                            .rootFieldReferenceInvocations()
                            .size
                }
            if (run.selectedCase == null) {
                run.assertAggregate(
                    generatedReferences > 0,
                    "Root-field-reference profile generated no references",
                )
                run.assertAggregate(
                    activatedReferences > 0,
                    "Root-field-reference profile activated no references",
                )
            }
        }
}

/** Generated contract for active fields exceptionally supplied by ancestor resolver outputs. */
interface SometimesPassiveGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated sometimes-passive fields resolve correctly`(): Unit =
        runBlocking {
            val assertions =
                generatedCaseAssertions.filterNot { assertion ->
                    assertion === GeneratedCaseAssertions.exactOrdinaryApplicationCounts
                }
            val config =
                Config.default +
                    (FieldArgumentWeight to 0.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false) +
                    (SometimesPassiveFieldWeight to 1.0)

            fun property(
                coverage: SometimesPassiveCoverage,
            ): suspend (TestWorld, ResolverTestCase) -> Unit =
                { testWorld, testCase ->
                    coverage.generatedFields +=
                        testCase.registry.features.sometimesPassiveFieldCount
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(
                            testWorld = testWorld,
                            testCase = testCase,
                            assertions = assertions,
                        )
                    val occurrenceCounts =
                        observation.ordinary.result.registeredResolverOccurrenceCounts(
                            observation.ordinary.operation,
                            observation.ordinary.world.resolverRegistry,
                        )
                    val applicationCounts =
                        observation.ordinaryApplications
                            .groupingBy(ResolverApplicationRecord::key)
                            .eachCount()
                    applicationCounts.forEach { (key, count) ->
                        assertTrue(
                            count <= occurrenceCounts.getOrDefault(key, 0),
                            "Observed $count applications of $key but result contains only " +
                                "${occurrenceCounts.getOrDefault(key, 0)} occurrences",
                        )
                    }
                    coverage.activatedOccurrences +=
                        occurrenceCounts.values.sum() - observation.ordinaryApplications.size
                }

            val sampledCoverage = SometimesPassiveCoverage()
            val sampledRun =
                checkGeneratedProfile(
                    profile = "sometimes-passive",
                    config = config,
                    property = property(sampledCoverage),
                )
            if (sampledRun.selectedCase == null) {
                val activationCoverage: SometimesPassiveCoverage
                val activationRun: ResolverTestRun
                if (sampledRun.seed == SOMETIMES_PASSIVE_ACTIVATION_SEED) {
                    activationCoverage = sampledCoverage
                    activationRun = sampledRun
                } else {
                    activationCoverage = SometimesPassiveCoverage()
                    activationRun =
                        checkGeneratedProfile(
                            profile = "sometimes-passive",
                            config = config,
                            seed = SOMETIMES_PASSIVE_ACTIVATION_SEED,
                            property = property(activationCoverage),
                        )
                }

                activationRun.assertAggregate(
                    activationCoverage.generatedFields > 0,
                    "Sometimes-passive activation corpus produced no source-owned fields",
                )
                activationRun.assertAggregate(
                    activationCoverage.activatedOccurrences > 0,
                    "Sometimes-passive activation corpus activated no source-owned fields",
                )
            }
        }
}

/**
 * Generated contract for source-level node resolution through fixture-lowered loaders.
 */
interface NodeGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated node worlds resolve correctly`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to nodeRootFieldReferencesEnabled) +
                    (NodeObjectWeight to 0.35) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)

            fun property(
                coverage: NodeCoverage,
            ): suspend (TestWorld, ResolverTestCase) -> Unit =
                { testWorld, testCase ->
                    assertTrue(testCase.registry.objectFragmentSources.values.all(String::isEmpty))
                    assertEquals(0, testCase.registry.features.variableCount)
                    coverage.generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
                    val nonNodeTypes =
                        testCase.schema.domainObjectTypeNames -
                            testCase.registry.nodeResolverTypes
                    if (
                        testCase.registry.nodeResolverTypes.isNotEmpty() &&
                        nonNodeTypes.isNotEmpty()
                    ) {
                        coverage.generatedMixedTopologyCases += 1
                    }

                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                    val activatedNodeLoader =
                        observation.ordinaryApplications.any { application ->
                            testCase.registry
                                .nodeLoaderPossibleTypes(
                                    testCase.schema,
                                    application.key.field,
                                ).isNotEmpty()
                        }
                    if (activatedNodeLoader) {
                        coverage.nodeLoaderApplications += 1
                    }
                    if (
                        activatedNodeLoader &&
                        testCase.registry.nodeResolverTypes.isNotEmpty() &&
                        nonNodeTypes.isNotEmpty()
                    ) {
                        coverage.activatedMixedTopologyCases += 1
                    }
                }

            val sampledCoverage = NodeCoverage()
            val run = checkGeneratedProfile("node", config, property = property(sampledCoverage))
            if (run.selectedCase == null) {
                val activationCoverage: NodeCoverage
                val activationRun: ResolverTestRun
                if (run.seed == NODE_ACTIVATION_SEED) {
                    activationCoverage = sampledCoverage
                    activationRun = run
                } else {
                    activationCoverage = NodeCoverage()
                    activationRun =
                        checkGeneratedProfile(
                            profile = "node",
                            config = config,
                            seed = NODE_ACTIVATION_SEED,
                            property = property(activationCoverage),
                        )
                }

                activationRun.assertAggregate(
                    activationCoverage.generatedNodeResolvers > 0,
                    "Node activation corpus produced no node resolvers",
                )
                activationRun.assertAggregate(
                    activationCoverage.nodeLoaderApplications > 0,
                    "Node activation corpus activated no fixture-lowered node loaders",
                )
                activationRun.assertAggregate(
                    activationCoverage.generatedMixedTopologyCases > 0,
                    "Node activation corpus produced no mixed node/non-node schemas",
                )
                activationRun.assertAggregate(
                    activationCoverage.activatedMixedTopologyCases > 0,
                    "Node activation corpus activated no node loaders in mixed schemas",
                )
            }
        }
}

/** Generated Resolver26 contract for selection-aware fixture-lowered node loaders. */
interface SelectiveNodeGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated selective node worlds resolve correctly with supplied payload demand`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to nodeRootFieldReferencesEnabled) +
                    (SelectiveNodeResolversEnabled to true) +
                    (NodeObjectWeight to 0.35) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)
            val emptyDemand = selectionForestOf().resolutionDigest()

            fun property(
                coverage: SelectiveNodeCoverage,
            ): suspend (TestWorld, ResolverTestCase) -> Unit =
                { testWorld, testCase ->
                    coverage.generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(testWorld, testCase, captureSuppliedDemand = true)
                    val nodeLoaderApplications =
                        observation.ordinaryApplications.filter { application ->
                            testCase.registry
                                .nodeLoaderPossibleTypes(
                                    testCase.schema,
                                    application.key.field,
                                ).isNotEmpty()
                        }
                    val callbackDemands =
                        observation.selectiveNodeResolverApplications.map { application ->
                            application.suppliedDemandFingerprint
                        }
                    assertTrue(
                        callbackDemands.size <= nodeLoaderApplications.size,
                        "A Query.node dispatch may invoke at most one selective node callback",
                    )
                    coverage.nodeLoaderApplications += nodeLoaderApplications.size
                    coverage.nonemptyDemandApplications +=
                        callbackDemands.count { demand ->
                            demand != emptyDemand
                        }
                }

            val sampledCoverage = SelectiveNodeCoverage()
            val run =
                checkGeneratedProfile(
                    profile = "selective-node",
                    config = config,
                    property = property(sampledCoverage),
                )
            if (run.selectedCase == null) {
                val activationCoverage: SelectiveNodeCoverage
                val activationRun: ResolverTestRun
                if (run.seed == NODE_ACTIVATION_SEED) {
                    activationCoverage = sampledCoverage
                    activationRun = run
                } else {
                    activationCoverage = SelectiveNodeCoverage()
                    activationRun =
                        checkGeneratedProfile(
                            profile = "selective-node",
                            config = config,
                            seed = NODE_ACTIVATION_SEED,
                            property = property(activationCoverage),
                        )
                }

                activationRun.assertAggregate(
                    activationCoverage.generatedNodeResolvers > 0,
                    "Selective-node activation corpus produced no node resolvers",
                )
                activationRun.assertAggregate(
                    activationCoverage.nodeLoaderApplications > 0,
                    "Selective-node activation corpus activated no fixture-lowered node loaders",
                )
                activationRun.assertAggregate(
                    activationCoverage.nonemptyDemandApplications > 0,
                    "Selective-node activation corpus supplied no nonempty node payload demand",
                )
            }
        }
}

/**
 * Generated contract for nonempty object fragments without variables.
 */
interface ObjectFragmentGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated object fragment worlds without variables resolve correctly`(): Unit =
        runBlocking {
            var generatedNonemptyFragments = 0
            var activatedNonemptyFragments = 0
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (InputListTypeWeight to 0.0) +
                    (InputObjectCount to 2..3) +
                    (InputObjectTypeWeight to 0.6) +
                    (MaxInputTypeDepth to 2) +
                    (NullableTypeWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false)

            val run = checkGeneratedProfile("object-fragment", config) { testWorld, testCase ->
                assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
                assertEquals(0, testCase.registry.features.variableCount)
                generatedNonemptyFragments +=
                    testCase.registry.objectFragmentSources.values.count(String::isNotEmpty)

                val observation =
                    observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                activatedNonemptyFragments +=
                    observation.ordinaryApplications.count { application ->
                        testCase.registry.hasNonemptyObjectFragment(application)
                    }
            }

            run.assertAggregate(
                generatedNonemptyFragments > 0,
                "Generated object-fragment profile produced no nonempty fragments",
            )
            run.assertAggregate(
                activatedNonemptyFragments > 0,
                "Generated object-fragment profile activated no nonempty fragments",
            )
        }
}

/** Generated contract for independently resolved field-resolver query fragments. */
interface QueryFragmentGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    val queryFragmentObjectPathVariablesEnabled: Boolean
        get() = false
    val queryFragmentQueryPathVariablesEnabled: Boolean
        get() = false

    @Test
    fun `generated query fragment worlds resolve correctly`(): Unit =
        runBlocking {
            var generatedQueryFragments = 0
            var generatedArgumentVariables = 0
            var generatedObjectPathVariables = 0
            var generatedQueryPathVariables = 0
            var activatedQueryFragments = 0
            var activatedArgumentVariableApplications = 0
            var activatedQueryPathVariableApplications = 0
            var queryValueWitnesses = 0
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverQueryFragmentsEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to
                        (queryFragmentObjectPathVariablesEnabled ||
                            queryFragmentQueryPathVariablesEnabled)) +
                    (ResolverFromQueryFieldVariablesEnabled to
                        queryFragmentQueryPathVariablesEnabled) +
                    generatedResolverConfigOverrides

            val run =
                checkGeneratedProfile("query-fragment", config) { testWorld, testCase ->
                    generatedQueryFragments += testCase.registry.features.queryFragmentCount
                    generatedArgumentVariables +=
                        testCase.registry.features.fromArgumentVariableCount
                    generatedObjectPathVariables +=
                        testCase.registry.features.fromObjectFieldVariableCount
                    generatedQueryPathVariables +=
                        testCase.registry.features.fromQueryFieldVariableCount

                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(
                            testWorld,
                            testCase,
                        )
                    activatedQueryFragments +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.hasNonemptyQueryFragment(application)
                        }
                    activatedArgumentVariableApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        }
                    activatedQueryPathVariableApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverHasFromQueryFieldVariables(
                                application.key.field,
                            )
                        }
                    queryValueWitnesses +=
                        observation.executions.sumOf { execution ->
                            (execution.operation.resolverObserver as CorrectnessResolverObserver)
                                .allQueryFragmentResults()
                                .size
                        }
                }

            run.assertAggregate(
                generatedQueryFragments > 0,
                "Generated query-fragment profile produced no query fragments",
            )
            run.assertAggregate(
                generatedArgumentVariables > 0,
                "Generated query-fragment profile produced no FromArgument variables",
            )
            if (queryFragmentObjectPathVariablesEnabled) {
                run.assertAggregate(
                    generatedObjectPathVariables > 0,
                    "Generated query-fragment profile produced no FromObjectField variables",
                )
            }
            if (queryFragmentQueryPathVariablesEnabled) {
                run.assertAggregate(
                    generatedQueryPathVariables > 0,
                    "Generated query-fragment profile produced no FromQueryField variables",
                )
                run.assertAggregate(
                    activatedQueryPathVariableApplications > 0,
                    "Generated query-fragment profile activated no FromQueryField variables",
                )
            }
            run.assertAggregate(
                activatedQueryFragments > 0,
                "Generated query-fragment profile activated no query fragments",
            )
            run.assertAggregate(
                activatedArgumentVariableApplications > 0,
                "Generated query-fragment profile activated no FromArgument variables",
            )
            run.assertAggregate(
                queryValueWitnesses > 0,
                "Generated query-fragment profile produced no query-value witnesses",
            )
        }
}

/**
 * Generated contract for nonempty object fragments with variables bound from resolver arguments.
 */
interface ObjectFragmentFromArgumentGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated object fragment worlds with fromArgument resolve correctly`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentNestedPathWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false) +
                    generatedResolverConfigOverrides

            fun property(
                coverage: FromArgumentCoverage,
            ): suspend (TestWorld, ResolverTestCase) -> Unit =
                { testWorld, testCase ->
                    assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
                    assertEquals(
                        testCase.registry.features.fromArgumentVariableCount,
                        testCase.registry.features.variableCount,
                    )
                    coverage.generatedVariables +=
                        testCase.registry.features.fromArgumentVariableCount
                    coverage.generatedNestedPaths +=
                        testCase.registry.features.fromArgumentNestedPathVariableCount
                    coverage.generatedNullableTraversals +=
                        testCase.registry.features.fromArgumentNullableTraversalVariableCount

                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                    coverage.activatedApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        }
                    coverage.activatedNestedPathApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverCoordinate(application.key.field) in
                                testCase.registry.nestedFromArgumentVariableOwnerFields
                        }
                    coverage.activatedNullableTraversalApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverCoordinate(application.key.field) in
                                testCase.registry
                                    .nullableTraversalFromArgumentVariableOwnerFields
                        }
                }

            val sampledCoverage = FromArgumentCoverage()
            val run =
                checkGeneratedProfile(
                    profile = "object-fragment-from-argument",
                    config = config,
                    property = property(sampledCoverage),
                )
            if (run.selectedCase == null) {
                val activationCoverage: FromArgumentCoverage
                val activationRun: ResolverTestRun
                if (run.seed == FROM_ARGUMENT_ACTIVATION_SEED) {
                    activationCoverage = sampledCoverage
                    activationRun = run
                } else {
                    activationCoverage = FromArgumentCoverage()
                    activationRun =
                        checkGeneratedProfile(
                            profile = "object-fragment-from-argument",
                            config = config,
                            seed = FROM_ARGUMENT_ACTIVATION_SEED,
                            property = property(activationCoverage),
                        )
                }

                activationRun.assertAggregate(
                    activationCoverage.generatedVariables > 0,
                    "FromArgument activation corpus produced no FromArgument variables",
                )
                activationRun.assertAggregate(
                    activationCoverage.activatedApplications > 0,
                    "FromArgument activation corpus activated no variable-bearing resolvers",
                )
                activationRun.assertAggregate(
                    activationCoverage.generatedNestedPaths > 0,
                    "FromArgument activation corpus produced no nested argument paths",
                )
                activationRun.assertAggregate(
                    activationCoverage.activatedNestedPathApplications > 0,
                    "FromArgument activation corpus activated no nested argument paths",
                )
                activationRun.assertAggregate(
                    activationCoverage.generatedNullableTraversals > 0,
                    "FromArgument activation corpus produced no nullable input traversal",
                )
                activationRun.assertAggregate(
                    activationCoverage.activatedNullableTraversalApplications > 0,
                    "FromArgument activation corpus activated no nullable input traversal",
                )
            }
        }
}

/** Generated contract isolating variables read from object-fragment paths. */
interface ObjectFragmentFromObjectPathGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    val objectPathGeneratorConfigOverrides: Config
        get() = Config.default

    @Test
    fun `generated object fragment worlds with fromObjectField resolve correctly`(): Unit =
        runBlocking {
            var generatedVariables = 0
            var generatedNestedProviderPaths = 0
            var generatedProviderArgumentVariables = 0
            var activatedApplications = 0
            var activatedNestedProviderApplications = 0
            var activatedProviderArgumentApplications = 0
            val config =
                Config.default +
                    (SchemaObjectCount to 4..6) +
                    (ObjectFieldCount to 4..6) +
                    (FieldArgumentWeight to 0.5) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (QueryFieldCount to 6..6) +
                    (RootQueryFieldCount to 10..10) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverNestedProviderPathWeight to 1.0) +
                    (ResolverFromFieldProviderArgumentVariableWeight to 1.0) +
                    (ResolverVariableCount to 2..4) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to true) +
                    objectPathGeneratorConfigOverrides +
                    generatedResolverConfigOverrides

            val run =
                checkGeneratedProfile(
                    "object-fragment-from-object-field",
                    config,
                ) { testWorld, testCase ->
                    val features = testCase.registry.features
                    assertEquals(
                        features.fromObjectFieldVariableCount,
                        features.variableCount,
                    )
                    generatedVariables += features.fromObjectFieldVariableCount
                    if (features.maximumFromObjectFieldPathLength > 1) {
                        generatedNestedProviderPaths += 1
                    }
                    generatedProviderArgumentVariables +=
                        features.fromObjectFieldProviderArgumentVariableCount
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                    activatedApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverHasFromObjectFieldVariables(
                                application.key.field,
                            )
                        }
                    activatedNestedProviderApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverHasNestedFromObjectFieldVariable(
                                application.key.field,
                            )
                        }
                    activatedProviderArgumentApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverCoordinate(application.key.field) in
                                testCase.registry
                                    .fromObjectFieldProviderArgumentVariableOwnerFields
                        }
                }

            run.assertAggregate(
                generatedVariables > 0,
                "Generated FromObjectField profile produced no path variables",
            )
            run.assertAggregate(
                generatedNestedProviderPaths > 0,
                "Generated FromObjectField profile produced no nested provider paths",
            )
            run.assertAggregate(
                generatedProviderArgumentVariables > 0,
                "Generated FromObjectField profile produced no provider-argument dependencies",
            )
            run.assertAggregate(
                activatedApplications > 0,
                "Generated FromObjectField profile activated no variable-bearing resolvers",
            )
            run.assertAggregate(
                activatedNestedProviderApplications > 0,
                "Generated FromObjectField profile activated no resolver with a nested provider path",
            )
            run.assertAggregate(
                activatedProviderArgumentApplications > 0,
                "Generated FromObjectField profile activated no provider-argument dependency",
            )
        }
}

/** Generated contract isolating variables returned by the resolver's one-shot provider. */
interface FromProviderGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated fromProvider variable worlds resolve correctly`(): Unit =
        runBlocking {
            var generatedVariables = 0
            var generatedArgumentSensitiveVariables = 0
            var activatedApplications = 0
            var activatedArgumentSensitiveApplications = 0
            var observedArgumentSensitiveValues = 0
            val config =
                Config.default +
                    (SchemaObjectCount to 4..6) +
                    (ObjectFieldCount to 4..6) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (QueryFieldCount to 6..6) +
                    (RootQueryFieldCount to 10..10) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFragmentArgumentFieldWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverFromProviderVariablesEnabled to true) +
                    (ResolverVariableCount to 2..4) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false) +
                    generatedResolverConfigOverrides

            val run =
                checkGeneratedProfile(
                    "from-provider",
                    config,
                ) { testWorld, testCase ->
                    val features = testCase.registry.features
                    assertEquals(features.fromProviderVariableCount, features.variableCount)
                    generatedVariables += features.fromProviderVariableCount
                    generatedArgumentSensitiveVariables +=
                        features.fromProviderArgumentSensitiveVariableCount

                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                    activatedApplications +=
                        observation.ordinaryApplications.count { application ->
                            testCase.registry.sourceResolverHasFromProviderVariables(
                                application.key.field,
                            )
                        }
                    activatedArgumentSensitiveApplications +=
                        observation.ordinaryApplications.count { application ->
                            val source =
                                testCase.registry.sourceResolverCoordinate(application.key.field)
                            source in testCase.registry.fromProviderVariableOwnerFields &&
                                application.key.arguments.fieldValues.isNotEmpty()
                        }
                    observation.ordinaryApplications
                        .filter { application ->
                            testCase.registry.sourceResolverHasFromProviderVariables(
                                application.key.field,
                            )
                        }.groupBy { application ->
                            testCase.registry.sourceResolverCoordinate(application.key.field)
                        }.forEach { (source, applications) ->
                            val arguments =
                                applications
                                    .map { application -> application.key.arguments }
                                    .distinct()
                            if (arguments.size < 2) return@forEach
                            val field =
                                observation.ordinary.world.schema.requireObjectField(
                                    source.typeName,
                                    source.fieldName,
                                )
                            val provider =
                                requireNotNull(
                                    observation.ordinary.world.resolverRegistry
                                        .resolver(field)
                                        .variablesProvider,
                                )
                            val first = provider(arguments[0])
                            assertEquals(first, provider(arguments[0]))
                            val second = provider(arguments[1])
                            if (first != second) {
                                assertNotEquals(first, second)
                                observedArgumentSensitiveValues += 1
                            }
                        }
                }

            run.assertAggregate(
                generatedVariables > 0,
                "Generated FromProvider profile produced no provider variables",
            )
            run.assertAggregate(
                generatedArgumentSensitiveVariables > 0,
                "Generated FromProvider profile produced no argument-sensitive providers",
            )
            run.assertAggregate(
                activatedApplications > 0,
                "Generated FromProvider profile activated no provider-bearing resolvers",
            )
            run.assertAggregate(
                activatedArgumentSensitiveApplications > 0,
                "Generated FromProvider profile activated no argument-sensitive provider",
            )
            run.assertAggregate(
                observedArgumentSensitiveValues > 0,
                "Generated FromProvider profile observed no argument-dependent callback values",
            )
        }
}

/** Generated contract for interactions between both resolver-variable sources. */
interface MixedVariableGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated mixed resolver variable worlds resolve correctly`(): Unit =
        runBlocking {
            val config =
                Config.default +
                    (SchemaObjectCount to 3..4) +
                    (ObjectFieldCount to 3..4) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (QueryFieldCount to 4..4) +
                    (RootQueryFieldCount to 10..10) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to true) +
                    generatedResolverConfigOverrides

            fun property(
                coverage: MixedVariableCoverage,
            ): suspend (TestWorld, ResolverTestCase) -> Unit =
                { testWorld, testCase ->
                    coverage.generatedFromArgument +=
                        testCase.registry.features.fromArgumentVariableCount
                    coverage.generatedFromObjectField +=
                        testCase.registry.features.fromObjectFieldVariableCount
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                    val fromArgument =
                        observation.ordinaryApplications.any { application ->
                            testCase.registry.sourceResolverHasFromArgumentVariables(
                                application.key.field,
                            )
                        }
                    val fromObjectField =
                        observation.ordinaryApplications.any { application ->
                            testCase.registry.sourceResolverHasFromObjectFieldVariables(
                                application.key.field,
                            )
                        }
                    if (fromArgument && fromObjectField) {
                        coverage.coactivatedCases += 1
                    }
                }

            val sampledCoverage = MixedVariableCoverage()
            val run =
                checkGeneratedProfile(
                    profile = "mixed-variables",
                    config = config,
                    property = property(sampledCoverage),
                )
            if (run.selectedCase == null) {
                val activationCoverage: MixedVariableCoverage
                val activationRun: ResolverTestRun
                if (run.seed == MIXED_VARIABLE_ACTIVATION_SEED) {
                    activationCoverage = sampledCoverage
                    activationRun = run
                } else {
                    activationCoverage = MixedVariableCoverage()
                    activationRun =
                        checkGeneratedProfile(
                            profile = "mixed-variables",
                            config = config,
                            seed = MIXED_VARIABLE_ACTIVATION_SEED,
                            property = property(activationCoverage),
                        )
                }

                activationRun.assertAggregate(
                    activationCoverage.generatedFromArgument > 0 &&
                        activationCoverage.generatedFromObjectField > 0,
                    "Mixed-variable activation corpus did not generate both variable kinds",
                )
                activationRun.assertAggregate(
                    activationCoverage.coactivatedCases > 0,
                    "Mixed-variable activation corpus did not coactivate both variable kinds",
                )
            }
        }
}

/**
 * Generated interaction-depth contract for the full supported feature combination.
 *
 * The narrower contracts isolate failures by capability. This contract preserves broad randomized
 * pressure across nodes, object fragments, arguments, and `FromArgument` variables together.
 */
interface FeatureInteractionGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated full feature interactions resolve correctly`(): Unit =
        runBlocking {
            var generatedFromArgumentVariables = 0
            var generatedMixedTopologyCases = 0
            var activatedMixedTopologyCases = 0
            var coactivatedNodeAndFromArgumentCases = 0
            var activatedImplementationDefaults = 0
            val config =
                Config.default +
                    (ImplementationArgumentDefaultWeight to 1.0) +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to nodeRootFieldReferencesEnabled) +
                    (NodeObjectWeight to 0.35) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false) +
                    generatedResolverConfigOverrides

            val run = checkGeneratedFeatureInteractionProfile(config) { testWorld, testCase ->
                val registry = testCase.registry
                val nonNodeTypes =
                    testCase.schema.domainObjectTypeNames - registry.nodeResolverTypes
                generatedFromArgumentVariables +=
                    registry.features.fromArgumentVariableCount
                if (
                    registry.nodeResolverTypes.isNotEmpty() &&
                    nonNodeTypes.isNotEmpty()
                ) {
                    generatedMixedTopologyCases += 1
                }
                if (testCase.query.features.hasAbstractImplementationDefaultSelection) {
                    activatedImplementationDefaults += 1
                }

                val observation =
                    observeGeneratedCaseWithCurrentAssertions(testWorld, testCase)
                val activatedNodeLoader =
                    observation.ordinaryApplications.any { application ->
                        registry
                            .nodeLoaderPossibleTypes(
                                testCase.schema,
                                application.key.field,
                            ).isNotEmpty()
                    }
                val activatedFromArgument =
                    observation.ordinaryApplications.any { application ->
                        registry.sourceResolverHasFromArgumentVariables(
                            application.key.field,
                        )
                    }
                val activatedNonNodeObject =
                    observation.ordinaryApplications.any { application ->
                        application.key.field.typeName in nonNodeTypes
                    }

                if (activatedNodeLoader && activatedNonNodeObject) {
                    activatedMixedTopologyCases += 1
                }
                if (activatedNodeLoader && activatedFromArgument) {
                    coactivatedNodeAndFromArgumentCases += 1
                }
            }

            run.assertAggregate(
                generatedFromArgumentVariables > 0,
                "Feature-interaction profile produced no FromArgument variables",
            )
            if (nodeRootFieldReferencesEnabled) {
                run.assertAggregate(
                    generatedMixedTopologyCases > 0,
                    "Feature-interaction profile produced no mixed node/non-node schemas",
                )
                run.assertAggregate(
                    activatedMixedTopologyCases > 0,
                    "Feature-interaction profile activated no mixed node/non-node schemas",
                )
                run.assertAggregate(
                    coactivatedNodeAndFromArgumentCases > 0,
                    "Feature-interaction profile never coactivated a node loader and FromArgument",
                )
            }
            run.assertAggregate(
                activatedImplementationDefaults > 0,
                "Feature-interaction profile activated no abstract implementation defaults",
            )
        }
}

private const val GENERATED_PROFILE_CASE_BUDGET = 150
private const val FEATURE_INTERACTION_CASE_BUDGET = 300
private const val NODE_ACTIVATION_SEED = 1L
private const val FROM_ARGUMENT_ACTIVATION_SEED = 1L
private const val MIXED_VARIABLE_ACTIVATION_SEED = 1L
private const val SOMETIMES_PASSIVE_ACTIVATION_SEED = 1L

private val GENERATED_PROFILE_COUNTS =
    TestCaseCount(
        schemas = 10,
        registriesPerSchema = 3,
        queriesPerSchema = 5,
    )

private val FEATURE_INTERACTION_PROFILE_COUNTS =
    TestCaseCount(
        schemas = 20,
        registriesPerSchema = 3,
        queriesPerSchema = 5,
    )

private data class NodeCoverage(
    var generatedNodeResolvers: Int = 0,
    var nodeLoaderApplications: Int = 0,
    var generatedMixedTopologyCases: Int = 0,
    var activatedMixedTopologyCases: Int = 0,
)

private data class SelectiveNodeCoverage(
    var generatedNodeResolvers: Int = 0,
    var nodeLoaderApplications: Int = 0,
    var nonemptyDemandApplications: Int = 0,
)

private data class FromArgumentCoverage(
    var generatedVariables: Int = 0,
    var activatedApplications: Int = 0,
    var generatedNestedPaths: Int = 0,
    var activatedNestedPathApplications: Int = 0,
    var generatedNullableTraversals: Int = 0,
    var activatedNullableTraversalApplications: Int = 0,
)

private data class MixedVariableCoverage(
    var generatedFromArgument: Int = 0,
    var generatedFromObjectField: Int = 0,
    var coactivatedCases: Int = 0,
)

private data class SometimesPassiveCoverage(
    var generatedFields: Int = 0,
    var activatedOccurrences: Int = 0,
)

internal suspend fun checkGeneratedProfile(
    profile: String,
    config: Config,
    seed: Long? = null,
    fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkGeneratedCases(
        profile = profile,
        counts = GENERATED_PROFILE_COUNTS,
        expectedCases = GENERATED_PROFILE_CASE_BUDGET,
        config = config,
        seed = seed,
        fieldCheckerMode = fieldCheckerMode,
        property = property,
    )

private suspend fun checkGeneratedFeatureInteractionProfile(
    config: Config,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkGeneratedCases(
        profile = "feature-interaction",
        counts = FEATURE_INTERACTION_PROFILE_COUNTS,
        expectedCases = FEATURE_INTERACTION_CASE_BUDGET,
        config = config,
        property = property,
    )

private suspend fun checkGeneratedCases(
    profile: String,
    counts: TestCaseCount,
    expectedCases: Int,
    config: Config,
    seed: Long? = null,
    fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkResolverTestCases(
        counts = counts,
        config = config,
        profile = profile,
        seed = seed,
        fieldCheckerMode = fieldCheckerMode,
        property = property,
    ).also { run ->
        val effectiveExpectedCases =
            if (run.sizeOverridden) {
                run.expectedCases
            } else {
                expectedCases
            }
        run.assertAggregate(
            run.attemptedCases == effectiveExpectedCases,
            "Generated $profile profile ran ${run.attemptedCases} cases; " +
                "expected $effectiveExpectedCases",
        )
    }

internal fun GeneratedCaseAssertionPolicy.observeGeneratedCaseWithCurrentAssertions(
    testWorld: TestWorld,
    testCase: ResolverTestCase,
    assertions: List<GeneratedCaseAssertion> = generatedCaseAssertions,
    captureSuppliedDemand: Boolean = false,
): GeneratedCaseObservation =
    observeGeneratedCase(testWorld, testCase, captureSuppliedDemand)
        .assertAll(assertions)

private fun ArbitraryRegistry.hasNonemptyObjectFragment(
    application: ResolverApplicationRecord,
): Boolean =
    objectFragmentSources[application.key.field]?.isNotEmpty() == true

private fun ArbitraryRegistry.hasNonemptyQueryFragment(
    application: ResolverApplicationRecord,
): Boolean =
    queryFragmentSources[application.key.field]?.isNotEmpty() == true
