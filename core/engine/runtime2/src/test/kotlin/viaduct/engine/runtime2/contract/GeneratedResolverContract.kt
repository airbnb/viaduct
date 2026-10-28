@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.ImplementationArgumentDefaultWeight
import viaduct.engine.runtime2.arbitrary.InputObjectCount
import viaduct.engine.runtime2.arbitrary.InputObjectTypeWeight
import viaduct.engine.runtime2.arbitrary.NodeObjectWeight
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.QueryFieldCount
import viaduct.engine.runtime2.arbitrary.ResolverApplicationRecord
import viaduct.engine.runtime2.arbitrary.ResolverFragmentArgumentFieldWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentNestedPathWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldProviderArgumentVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromProviderVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverNestedProviderPathWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.ResolverTestRun
import viaduct.engine.runtime2.arbitrary.ResolverVariableCount
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.RootQueryFieldCount
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.resolutionDigest
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite

/**
 * Generated contract for user-declared resolvers with empty object fragments and no variables.
 */
interface EmptyObjectFragmentGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated empty object fragment worlds resolve correctly`(): Unit =
        runBlocking {
            checkResolverTestSuite(ResolverTestSuiteDefinition.EMPTY_OBJECT_FRAGMENT) { testWorld, testCase ->
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
            val run =
                checkResolverTestSuite(ResolverTestSuiteDefinition.ROOT_FIELD_REFERENCE) { testWorld, testCase ->
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
            run.requireCoverage(
                generatedReferences > 0,
                "Root-field-reference profile generated no references",
            )
            run.requireCoverage(
                activatedReferences > 0,
                "Root-field-reference profile activated no references",
            )
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
            val definition = ResolverTestSuiteDefinition.SOMETIMES_PASSIVE

            val coverage = SometimesPassiveCoverage()
            val run =
                checkResolverTestSuite(definition) { testWorld, testCase ->
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
            run.requireCoverage(
                coverage.generatedFields > 0,
                "Sometimes-passive suite produced no source-owned fields",
            )
            run.requireCoverage(
                coverage.activatedOccurrences > 0,
                "Sometimes-passive suite activated no source-owned fields",
            )
        }
}

/**
 * Generated contract for source-level node resolution through fixture-lowered loaders.
 */
interface NodeGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated node worlds resolve correctly`(): Unit =
        runBlocking {
            assertTrue(nodeRootFieldReferencesEnabled, "The stored node suite generates node resolvers")
            val definition = ResolverTestSuiteDefinition.NODE

            val coverage = NodeCoverage()
            val run =
                checkResolverTestSuite(definition) { testWorld, testCase ->
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
            run.requireCoverage(
                coverage.generatedNodeResolvers > 0,
                "Node suite produced no node resolvers",
            )
            run.requireCoverage(
                coverage.nodeLoaderApplications > 0,
                "Node suite activated no fixture-lowered node loaders",
            )
            run.requireCoverage(
                coverage.generatedMixedTopologyCases > 0,
                "Node suite produced no mixed node/non-node schemas",
            )
            run.requireCoverage(
                coverage.activatedMixedTopologyCases > 0,
                "Node suite activated no node loaders in mixed schemas",
            )
        }
}

/** Generated Resolution contract for selection-aware fixture-lowered node loaders. */
interface SelectiveNodeGeneratedResolverContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated selective node worlds resolve correctly with supplied payload demand`(): Unit =
        runBlocking {
            assertTrue(nodeRootFieldReferencesEnabled, "The stored selective-node suite generates node resolvers")
            val definition = ResolverTestSuiteDefinition.SELECTIVE_NODE
            val emptyDemand = selectionForestOf().resolutionDigest()

            val coverage = SelectiveNodeCoverage()
            val run =
                checkResolverTestSuite(definition) { testWorld, testCase ->
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
            run.requireCoverage(
                coverage.generatedNodeResolvers > 0,
                "Selective-node suite produced no node resolvers",
            )
            run.requireCoverage(
                coverage.nodeLoaderApplications > 0,
                "Selective-node suite activated no fixture-lowered node loaders",
            )
            run.requireCoverage(
                coverage.nonemptyDemandApplications > 0,
                "Selective-node suite supplied no nonempty node payload demand",
            )
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
            val run = checkResolverTestSuite(ResolverTestSuiteDefinition.OBJECT_FRAGMENT) { testWorld, testCase ->
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

            run.requireCoverage(
                generatedNonemptyFragments > 0,
                "Generated object-fragment profile produced no nonempty fragments",
            )
            run.requireCoverage(
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
                    (
                        ResolverVariablesEnabled to
                            (
                                queryFragmentObjectPathVariablesEnabled ||
                                    queryFragmentQueryPathVariablesEnabled
                            )
                    ) +
                    (
                        ResolverFromQueryFieldVariablesEnabled to
                            queryFragmentQueryPathVariablesEnabled
                    ) +
                    generatedResolverConfigOverrides

            val run =
                checkStoredProfile("query-fragment", config) { testWorld, testCase ->
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

            run.requireCoverage(
                generatedQueryFragments > 0,
                "Generated query-fragment profile produced no query fragments",
            )
            run.requireCoverage(
                generatedArgumentVariables > 0,
                "Generated query-fragment profile produced no FromArgument variables",
            )
            if (queryFragmentObjectPathVariablesEnabled) {
                run.requireCoverage(
                    generatedObjectPathVariables > 0,
                    "Generated query-fragment profile produced no FromObjectField variables",
                )
            }
            if (queryFragmentQueryPathVariablesEnabled) {
                run.requireCoverage(
                    generatedQueryPathVariables > 0,
                    "Generated query-fragment profile produced no FromQueryField variables",
                )
                run.requireCoverage(
                    activatedQueryPathVariableApplications > 0,
                    "Generated query-fragment profile activated no FromQueryField variables",
                )
            }
            run.requireCoverage(
                activatedQueryFragments > 0,
                "Generated query-fragment profile activated no query fragments",
            )
            run.requireCoverage(
                activatedArgumentVariableApplications > 0,
                "Generated query-fragment profile activated no FromArgument variables",
            )
            run.requireCoverage(
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
                    (InputObjectCount to 1..2) +
                    (InputObjectTypeWeight to 0.5) +
                    (RootQueryFieldCount to 10..10) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverFromArgumentNestedPathWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false) +
                    generatedResolverConfigOverrides

            val coverage = FromArgumentCoverage()
            val run =
                checkStoredProfile("object-fragment-from-argument", config) { testWorld, testCase ->
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
            run.requireCoverage(
                coverage.generatedVariables > 0,
                "FromArgument suite produced no FromArgument variables",
            )
            run.requireCoverage(
                coverage.activatedApplications > 0,
                "FromArgument suite activated no variable-bearing resolvers",
            )
            run.requireCoverage(
                coverage.generatedNestedPaths > 0,
                "FromArgument suite produced no nested argument paths",
            )
            run.requireCoverage(
                coverage.activatedNestedPathApplications > 0,
                "FromArgument suite activated no nested argument paths",
            )
            run.requireCoverage(
                coverage.generatedNullableTraversals > 0,
                "FromArgument suite produced no nullable input traversal",
            )
            run.requireCoverage(
                coverage.activatedNullableTraversalApplications > 0,
                "FromArgument suite activated no nullable input traversal",
            )
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
                checkStoredProfile(
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

            run.requireCoverage(
                generatedVariables > 0,
                "Generated FromObjectField profile produced no path variables",
            )
            run.requireCoverage(
                generatedNestedProviderPaths > 0,
                "Generated FromObjectField profile produced no nested provider paths",
            )
            run.requireCoverage(
                generatedProviderArgumentVariables > 0,
                "Generated FromObjectField profile produced no provider-argument dependencies",
            )
            run.requireCoverage(
                activatedApplications > 0,
                "Generated FromObjectField profile activated no variable-bearing resolvers",
            )
            run.requireCoverage(
                activatedNestedProviderApplications > 0,
                "Generated FromObjectField profile activated no resolver with a nested provider path",
            )
            run.requireCoverage(
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
                checkStoredProfile(
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

            run.requireCoverage(
                generatedVariables > 0,
                "Generated FromProvider profile produced no provider variables",
            )
            run.requireCoverage(
                generatedArgumentSensitiveVariables > 0,
                "Generated FromProvider profile produced no argument-sensitive providers",
            )
            run.requireCoverage(
                activatedApplications > 0,
                "Generated FromProvider profile activated no provider-bearing resolvers",
            )
            run.requireCoverage(
                activatedArgumentSensitiveApplications > 0,
                "Generated FromProvider profile activated no argument-sensitive provider",
            )
            run.requireCoverage(
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

            val coverage = MixedVariableCoverage()
            val run =
                checkStoredProfile("mixed-variables", config) { testWorld, testCase ->
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
            run.requireCoverage(
                coverage.generatedFromArgument > 0 &&
                    coverage.generatedFromObjectField > 0,
                "Mixed-variable suite did not generate both variable kinds",
            )
            run.requireCoverage(
                coverage.coactivatedCases > 0,
                "Mixed-variable suite did not coactivate both variable kinds",
            )
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
                    (SchemaObjectCount to 3..4) +
                    (QueryFieldCount to 4..6) +
                    (RootQueryFieldCount to 10..10) +
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

            val run = checkStoredProfile("feature-interaction", config) { testWorld, testCase ->
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

            run.requireCoverage(
                generatedFromArgumentVariables > 0,
                "Feature-interaction profile produced no FromArgument variables",
            )
            if (nodeRootFieldReferencesEnabled) {
                run.requireCoverage(
                    generatedMixedTopologyCases > 0,
                    "Feature-interaction profile produced no mixed node/non-node schemas",
                )
                run.requireCoverage(
                    activatedMixedTopologyCases > 0,
                    "Feature-interaction profile activated no mixed node/non-node schemas",
                )
                run.requireCoverage(
                    coactivatedNodeAndFromArgumentCases > 0,
                    "Feature-interaction profile never coactivated a node loader and FromArgument",
                )
            }
            run.requireCoverage(
                activatedImplementationDefaults > 0,
                "Feature-interaction profile activated no abstract implementation defaults",
            )
        }
}

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

internal suspend fun checkStoredProfile(
    profile: String,
    config: Config,
    fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
    typeCheckerMode: GeneratedTypeCheckerMode = GeneratedTypeCheckerMode.NONE,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    checkResolverTestSuite(
        ResolverTestSuiteDefinition.forProfile(profile, config, fieldCheckerMode, typeCheckerMode),
        property = property,
    )

internal fun GeneratedCaseAssertionPolicy.observeGeneratedCaseWithCurrentAssertions(
    testWorld: TestWorld,
    testCase: ResolverTestCase,
    assertions: List<GeneratedCaseAssertion> = generatedCaseAssertions,
    captureSuppliedDemand: Boolean = false,
): GeneratedCaseObservation =
    observeGeneratedCase(testWorld, testCase, captureSuppliedDemand)
        .assertAll(assertions)

private fun ArbitraryRegistry.hasNonemptyObjectFragment(application: ResolverApplicationRecord): Boolean = objectFragmentSources[application.key.field]?.isNotEmpty() == true

private fun ArbitraryRegistry.hasNonemptyQueryFragment(application: ResolverApplicationRecord): Boolean = queryFragmentSources[application.key.field]?.isNotEmpty() == true
