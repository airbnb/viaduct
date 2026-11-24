package viaduct.engine.runtime2.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.ResolverApplicationRecord
import viaduct.engine.runtime2.arbitrary.ResolverFromObjectFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverProgramKind
import viaduct.engine.runtime2.arbitrary.ResolverProgramMutation
import viaduct.engine.runtime2.arbitrary.ResolverTestRun
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.resolutionDigest
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition

internal class ResolverPropertyCoverage(
    val record: suspend (GeneratedCaseObservation) -> Unit,
    val requireCovered: (ResolverTestRun) -> Unit = {},
)

internal fun ResolverContract.resolverPropertyCoverage(definition: ResolverTestSuiteDefinition): ResolverPropertyCoverage =
    when (definition.profile) {
        "empty-object-fragment" -> emptyObjectFragmentCoverage()
        "root-field-reference" -> rootFieldReferenceCoverage()
        "sometimes-passive" -> sometimesPassiveCoverage()
        "node" -> nodeCoverage()
        "selective-node" -> selectiveNodeCoverage()
        "object-fragment" -> objectFragmentCoverage()
        "query-fragment" -> queryFragmentCoverage(definition.config)
        "object-fragment-from-argument" -> objectFragmentFromArgumentCoverage()
        "object-fragment-from-object-field" -> objectFragmentFromObjectFieldCoverage()
        "from-provider" -> fromProviderCoverage()
        "mixed-variables" -> mixedVariablesCoverage()
        "feature-interaction" -> featureInteractionCoverage(definition)
        "list-passive-deepening" -> listPassiveDeepeningCoverage()
        "construction-witness" -> constructionWitnessCoverage()
        else -> checkerPropertyCoverage(definition)
    }

private fun emptyObjectFragmentCoverage(): ResolverPropertyCoverage {
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
            assertTrue(testCase.registry.objectFragmentSources.values.all(String::isEmpty))
            assertEquals(0, testCase.registry.features.variableCount)
        },
    )
}

private fun rootFieldReferenceCoverage(): ResolverPropertyCoverage {
    var generatedReferences = 0
    var activatedReferences = 0
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            generatedReferences +=
                testCase.registry.features.generatedRootFieldReferenceCount
            activatedReferences +=
                (observation.ordinary.operation.resolutionObserver as CorrectnessResolverObserver)
                    .rootFieldReferenceInvocations()
                    .size
        },
        requireCovered = { run ->
            run.requireCoverage(
                generatedReferences > 0,
                "Root-field-reference profile generated no references",
            )
            run.requireCoverage(
                activatedReferences > 0,
                "Root-field-reference profile activated no references",
            )
        },
    )
}

private fun sometimesPassiveCoverage(): ResolverPropertyCoverage {
    val coverage = SometimesPassiveCoverage()
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            coverage.generatedFields +=
                testCase.registry.features.sometimesPassiveFieldCount
            val occurrenceCounts =
                observation.ordinary.result.registeredResolverOccurrenceCounts(
                    observation.ordinary.operation,
                    observation.ordinary.world.resolverRegistry,
                )
            coverage.activatedOccurrences +=
                occurrenceCounts.values.sum() - observation.ordinaryApplications.size
        },
        requireCovered = { run ->
            run.requireCoverage(
                coverage.generatedFields > 0,
                "Sometimes-passive suite produced no source-owned fields",
            )
            run.requireCoverage(
                coverage.activatedOccurrences > 0,
                "Sometimes-passive suite activated no source-owned fields",
            )
        },
    )
}

private fun nodeCoverage(): ResolverPropertyCoverage {
    val coverage = NodeCoverage()
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
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
        },
        requireCovered = { run ->
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
        },
    )
}

private fun selectiveNodeCoverage(): ResolverPropertyCoverage {
    val emptyDemand = selectionForestOf().resolutionDigest()
    val coverage = SelectiveNodeCoverage()
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            coverage.generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
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
            coverage.nodeLoaderApplications += nodeLoaderApplications.size
            coverage.nonemptyDemandApplications +=
                callbackDemands.count { demand ->
                    demand != emptyDemand
                }
        },
        requireCovered = { run ->
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
        },
    )
}

private fun objectFragmentCoverage(): ResolverPropertyCoverage {
    var generatedNonemptyFragments = 0
    var activatedNonemptyFragments = 0
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            assertTrue(testCase.registry.nodeResolverTypes.isEmpty())
            assertEquals(0, testCase.registry.features.variableCount)
            generatedNonemptyFragments +=
                testCase.registry.objectFragmentSources.values.count(String::isNotEmpty)

            activatedNonemptyFragments +=
                observation.ordinaryApplications.count { application ->
                    testCase.registry.hasNonemptyObjectFragment(application)
                }
        },
        requireCovered = { run ->
            run.requireCoverage(
                generatedNonemptyFragments > 0,
                "Generated object-fragment profile produced no nonempty fragments",
            )
            run.requireCoverage(
                activatedNonemptyFragments > 0,
                "Generated object-fragment profile activated no nonempty fragments",
            )
        },
    )
}

internal fun ResolverContract.queryFragmentCoverage(config: Config): ResolverPropertyCoverage {
    var generatedQueryFragments = 0
    var generatedArgumentVariables = 0
    var generatedObjectPathVariables = 0
    var generatedQueryPathVariables = 0
    var activatedQueryFragments = 0
    var activatedArgumentVariableApplications = 0
    var activatedQueryPathVariableApplications = 0
    var queryInputSensitiveApplications = 0
    val queryInputMutationKillCases = linkedSetOf<String>()
    val queryInputMutationKillBatches = linkedSetOf<String>()
    var queryValueWitnesses = 0
    val mutants = ResolverInputMutants(this)
    val queryFragmentObjectPathVariablesEnabled = config[ResolverVariablesEnabled] && config[ResolverFromObjectFieldVariablesEnabled]
    val queryFragmentQueryPathVariablesEnabled = config[ResolverVariablesEnabled] && config[ResolverFromQueryFieldVariablesEnabled]
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            generatedQueryFragments += testCase.registry.features.queryFragmentCount
            generatedArgumentVariables +=
                testCase.registry.features.fromArgumentVariableCount
            generatedObjectPathVariables +=
                testCase.registry.features.fromObjectFieldVariableCount
            generatedQueryPathVariables +=
                testCase.registry.features.fromQueryFieldVariableCount

            val operationQueryFields = linkedSetOf<String>()
            observation.ordinary.fragment.subselections.forEach { selection ->
                operationQueryFields += selection.key.field.name
            }
            val queryFragmentApplications = observation.ordinaryApplications.filter { application ->
                testCase.registry.hasNonemptyQueryFragment(application) &&
                    testCase.registry.queryFragments.getValue(application.key.field).selections.any { selection ->
                        selection.fieldName !in operationQueryFields
                    }
            }
            if (queryFragmentApplications.isNotEmpty()) {
                val mutant = mutants.result(observation, ResolverProgramMutation.IGNORE_QUERY_FRAGMENT_INPUT)
                if (!mutant.sameCompletedResultAs(observation.ordinary.result)) {
                    testCase.testCaseId?.let(queryInputMutationKillCases::add)
                    testCase.testCaseId?.split("/")?.getOrNull(1)?.let(queryInputMutationKillBatches::add)
                }
            }
            queryInputSensitiveApplications += queryFragmentApplications.count { application ->
                testCase.registry.resolverProgram(application.key.field) in
                    setOf(ResolverProgramKind.INPUT_SENSITIVE, ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE)
            }
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
                    (execution.operation.resolutionObserver as CorrectnessResolverObserver)
                        .allQueryFragmentResults()
                        .size
                }
        },
        requireCovered = { run ->
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
                queryInputSensitiveApplications > 0,
                "Generated query-fragment profile activated no resolver whose output depends on its query input",
            )
            run.requireCoverage(
                queryInputMutationKillCases.size >= 5 && queryInputMutationKillBatches.size >= 2,
                "Generated query-fragment profile needs query-input mutation failures in at least five cases and two batches; " +
                    "observed ${queryInputMutationKillCases.size} cases in ${queryInputMutationKillBatches.size} batches",
            )
            run.requireCoverage(
                activatedArgumentVariableApplications > 0,
                "Generated query-fragment profile activated no FromArgument variables",
            )
            run.requireCoverage(
                queryValueWitnesses > 0,
                "Generated query-fragment profile produced no query-value witnesses",
            )
        },
    )
}

internal fun ResolverContract.parentReadingResolverInputCoverage(): ResolverPropertyCoverage {
    var invokedParentReaders = 0
    val parentInputMutationKillCases = linkedSetOf<String>()
    val parentInputMutationKillBatches = linkedSetOf<String>()
    var invokedDeepParentDemandResolvers = 0
    val deepParentDemandMutationKillCases = linkedSetOf<String>()
    val deepParentDemandMutationKillBatches = linkedSetOf<String>()
    val mutants = ResolverInputMutants(this)
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            invokedParentReaders += observation.ordinaryApplications.count { application ->
                application.key.field in testCase.registry.parentReadingResolverInputOwnerFields &&
                    testCase.registry.resolverProgram(application.key.field) in
                    setOf(ResolverProgramKind.INPUT_SENSITIVE, ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE)
            }
            val deepParentDemandApplications = observation.ordinaryApplications.filter { application ->
                (testCase.registry.parentDemandOwnerFields[application.key.field] ?: 0) >= 3 &&
                    testCase.registry.resolverProgram(application.key.field) in
                    setOf(ResolverProgramKind.INPUT_SENSITIVE, ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE)
            }
            invokedDeepParentDemandResolvers += deepParentDemandApplications.size
            if (deepParentDemandApplications.isNotEmpty()) {
                val mutant = mutants.result(observation, ResolverProgramMutation.IGNORE_DEEP_PARENT_DEMAND_INPUT)
                if (!mutant.sameCompletedResultAs(observation.ordinary.result)) {
                    testCase.testCaseId?.let(deepParentDemandMutationKillCases::add)
                    testCase.testCaseId?.split("/")?.getOrNull(1)?.let(deepParentDemandMutationKillBatches::add)
                }
            }
            if (observation.ordinaryApplications.any { application -> application.key.field in testCase.registry.parentReadingResolverInputOwnerFields }) {
                val mutant = mutants.result(observation, ResolverProgramMutation.IGNORE_PARENT_READING_INPUT)
                if (!mutant.sameCompletedResultAs(observation.ordinary.result)) {
                    testCase.testCaseId?.let(parentInputMutationKillCases::add)
                    testCase.testCaseId?.split("/")?.getOrNull(1)?.let(parentInputMutationKillBatches::add)
                }
            }
        },
        requireCovered = { run ->
            run.requireCoverage(
                invokedParentReaders > 0,
                "Generated parent-fields profile invoked no input-sensitive resolver that reads parent-dependent data",
            )
            run.requireCoverage(
                parentInputMutationKillCases.size >= 5 && parentInputMutationKillBatches.size >= 2,
                "Generated parent-fields profile needs parent-input mutation failures in at least five cases and two batches; " +
                    "observed ${parentInputMutationKillCases.size} cases in ${parentInputMutationKillBatches.size} batches",
            )
            run.requireCoverage(
                invokedDeepParentDemandResolvers >= 5,
                "Generated parent-fields profile invoked fewer than five input-sensitive resolvers with at least three parent-demand steps; " +
                    "observed $invokedDeepParentDemandResolvers",
            )
            run.requireCoverage(
                deepParentDemandMutationKillCases.size >= 5 && deepParentDemandMutationKillBatches.size >= 2,
                "Generated parent-fields profile needs deep parent-demand input mutation failures in at least five cases and two batches; " +
                    "observed ${deepParentDemandMutationKillCases.size} cases in ${deepParentDemandMutationKillBatches.size} batches",
            )
        },
    )
}

private class ResolverInputMutants(private val subject: ResolverContract) {
    private var registry: ArbitraryRegistry? = null
    private val worlds = mutableMapOf<ResolverProgramMutation, TestWorld>()

    fun result(
        observation: GeneratedCaseObservation,
        mutation: ResolverProgramMutation
    ): ObjectEngineResult {
        val testCase = observation.testCase
        if (registry !== testCase.registry) {
            registry = testCase.registry
            worlds.clear()
        }
        val world = worlds.getOrPut(mutation) {
            testCase.registry.world(
                schema = testCase.schema,
                resolverProgramMutation = mutation,
                selectiveResolvers = subject.selectiveResolvers,
            )
        }
        return subject.observeGeneratedCase(world, testCase, comparePermutation = false).ordinary.result
    }
}

private fun objectFragmentFromArgumentCoverage(): ResolverPropertyCoverage {
    val coverage = FromArgumentCoverage()
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
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
        },
        requireCovered = { run ->
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
        },
    )
}

private fun objectFragmentFromObjectFieldCoverage(): ResolverPropertyCoverage {
    var generatedVariables = 0
    var generatedNestedProviderPaths = 0
    var generatedProviderArgumentVariables = 0
    var activatedApplications = 0
    var activatedNestedProviderApplications = 0
    var activatedProviderArgumentApplications = 0
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
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
        },
        requireCovered = { run ->
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
        },
    )
}

private fun fromProviderCoverage(): ResolverPropertyCoverage {
    var generatedVariables = 0
    var generatedArgumentSensitiveVariables = 0
    var activatedApplications = 0
    var activatedArgumentSensitiveApplications = 0
    var observedArgumentSensitiveValues = 0
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            val features = testCase.registry.features
            assertEquals(features.fromProviderVariableCount, features.variableCount)
            generatedVariables += features.fromProviderVariableCount
            generatedArgumentSensitiveVariables +=
                features.fromProviderArgumentSensitiveVariableCount

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
                                .declaredDependencies.variablesProvider,
                        )
                    val first = provider(arguments[0])
                    assertEquals(first, provider(arguments[0]))
                    val second = provider(arguments[1])
                    if (first != second) {
                        assertNotEquals(first, second)
                        observedArgumentSensitiveValues += 1
                    }
                }
        },
        requireCovered = { run ->
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
        },
    )
}

private fun mixedVariablesCoverage(): ResolverPropertyCoverage {
    val coverage = MixedVariableCoverage()
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
            coverage.generatedFromArgument +=
                testCase.registry.features.fromArgumentVariableCount
            coverage.generatedFromObjectField +=
                testCase.registry.features.fromObjectFieldVariableCount
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
        },
        requireCovered = { run ->
            run.requireCoverage(
                coverage.generatedFromArgument > 0 &&
                    coverage.generatedFromObjectField > 0,
                "Mixed-variable suite did not generate both variable kinds",
            )
            run.requireCoverage(
                coverage.coactivatedCases > 0,
                "Mixed-variable suite did not coactivate both variable kinds",
            )
        },
    )
}

private fun featureInteractionCoverage(definition: ResolverTestSuiteDefinition): ResolverPropertyCoverage {
    var generatedFromArgumentVariables = 0
    var generatedMixedTopologyCases = 0
    var activatedMixedTopologyCases = 0
    var coactivatedNodeAndFromArgumentCases = 0
    var activatedImplementationDefaults = 0
    val nodeRootFieldReferencesEnabled = definition.config[NodeResolversEnabled]
    return ResolverPropertyCoverage(
        record = { observation ->
            val testCase = observation.testCase
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
        },
        requireCovered = { run ->
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
        },
    )
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

private fun ArbitraryRegistry.hasNonemptyObjectFragment(application: ResolverApplicationRecord): Boolean = objectFragmentSources[application.key.field]?.isNotEmpty() == true

private fun ArbitraryRegistry.hasNonemptyQueryFragment(application: ResolverApplicationRecord): Boolean = queryFragmentSources[application.key.field]?.isNotEmpty() == true
