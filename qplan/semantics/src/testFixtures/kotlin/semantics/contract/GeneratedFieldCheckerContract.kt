@file:Suppress("ForbiddenImport")

package semantics.contract

import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.GeneratedFieldCheckerMode
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.ResolverArgumentErrorWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentNestedPathWeight
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromObjectFieldVariablesEnabled
import semantics.arbitrary.ResolverFromProviderVariablesEnabled
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverQueryFragmentWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverTestCase
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.RootFieldReferenceWeight
import semantics.arbitrary.RootFieldReferencesEnabled
import semantics.arbitrary.SometimesPassiveFieldWeight

/** Grounded and symbolic field-checker coverage with independent exact application accounting. */
interface GeneratedFieldCheckerContract : GeneratedCaseAssertionPolicy {
    val runtimeFieldCheckerVariables: Boolean get() = false

    private fun checkerProfile(profile: String): String = if (runtimeFieldCheckerVariables) profile.replace("resolver23", "resolver26") else profile

    private fun checkerMode(mode: GeneratedFieldCheckerMode): GeneratedFieldCheckerMode =
        if (!runtimeFieldCheckerVariables) {
            mode
        } else {
            when (mode) {
                GeneratedFieldCheckerMode.SUCCESS -> GeneratedFieldCheckerMode.RUNTIME_SUCCESS
                GeneratedFieldCheckerMode.DENIAL -> GeneratedFieldCheckerMode.RUNTIME_DENIAL
                GeneratedFieldCheckerMode.MIXED -> GeneratedFieldCheckerMode.RUNTIME_MIXED
                else -> error("Not a grounded checker mode: $mode")
            }
        }

    @Test
    fun `generated successful field checker worlds resolve correctly`(): Unit =
        runGeneratedFieldCheckerProfile(
            profile = SUCCESS_PROFILE,
            mode = GeneratedFieldCheckerMode.SUCCESS,
            additionalRequired = setOf(GeneratedFieldCheckerCoverageSignature.CHECKER_SUCCESS),
        )

    @Test
    fun `generated denying field checker worlds resolve correctly`(): Unit =
        runGeneratedFieldCheckerProfile(
            profile = DENIAL_PROFILE,
            mode = GeneratedFieldCheckerMode.DENIAL,
            additionalRequired = setOf(GeneratedFieldCheckerCoverageSignature.CHECKER_DENIAL),
        )

    @Test
    fun `generated mixed field checker worlds resolve correctly`(): Unit =
        runGeneratedFieldCheckerProfile(
            profile = MIXED_PROFILE,
            mode = GeneratedFieldCheckerMode.MIXED,
            additionalRequired =
                setOf(
                    GeneratedFieldCheckerCoverageSignature.CHECKER_SUCCESS,
                    GeneratedFieldCheckerCoverageSignature.CHECKER_DENIAL,
                    GeneratedFieldCheckerCoverageSignature.RESOLVER_WITHOUT_CHECKER,
                ),
        )

    @Test
    fun `generated passive field checker worlds resolve correctly`(): Unit =
        runBlocking {
            val coverage = GeneratedFieldCheckerCoverage(checkerMode(GeneratedFieldCheckerMode.SUCCESS))
            val config =
                Config.default +
                    (FieldArgumentWeight to 0.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverQueryFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false) +
                    (SometimesPassiveFieldWeight to 1.0) +
                    generatedResolverConfigOverrides
            val assertions =
                generatedCaseAssertions + GeneratedCaseAssertions.exactCheckerApplications
            val run =
                checkGeneratedProfile(
                    profile = checkerProfile(PASSIVE_PROFILE),
                    config = config,
                    seed = PASSIVE_ACTIVATION_SEED,
                    fieldCheckerMode = checkerMode(GeneratedFieldCheckerMode.SUCCESS),
                ) { testWorld, testCase ->
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(
                            testWorld = testWorld,
                            testCase = testCase,
                            assertions = assertions,
                        )
                    coverage.record(testCase.registry, observation)
                }

            coverage.assertRequired(
                run = run,
                required =
                    setOf(
                        GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER,
                        GeneratedFieldCheckerCoverageSignature.PASSIVE_CHECKED_FIELD,
                    ),
            )
            println("Field-checker coverage profile=${checkerProfile(PASSIVE_PROFILE)} ${coverage.summary()}")
        }

    @Test
    fun `generated root-reference field checker worlds resolve correctly`(): Unit =
        runBlocking {
            val coverage = GeneratedFieldCheckerCoverage(checkerMode(GeneratedFieldCheckerMode.SUCCESS))
            val config =
                Config.default +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to false) +
                    (ResolverQueryFragmentsEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (ResolverVariablesEnabled to false) +
                    (RootFieldReferencesEnabled to true) +
                    (RootFieldReferenceWeight to 0.25) +
                    generatedResolverConfigOverrides
            val assertions =
                generatedCaseAssertions + GeneratedCaseAssertions.exactCheckerApplications
            val run =
                checkGeneratedProfile(
                    profile = checkerProfile(ROOT_REFERENCE_PROFILE),
                    config = config,
                    seed = ROOT_REFERENCE_ACTIVATION_SEED,
                    fieldCheckerMode = checkerMode(GeneratedFieldCheckerMode.SUCCESS),
                ) { testWorld, testCase ->
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(
                            testWorld = testWorld,
                            testCase = testCase,
                            assertions = assertions,
                        )
                    coverage.record(testCase.registry, observation)
                }

            coverage.assertRequired(
                run = run,
                required =
                    setOf(
                        GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER,
                        GeneratedFieldCheckerCoverageSignature.ROOT_FIELD_REFERENCE_RESULT,
                    ),
            )
            println(
                "Field-checker coverage profile=${checkerProfile(ROOT_REFERENCE_PROFILE)} " +
                    coverage.summary(),
            )
        }

    private fun runGeneratedFieldCheckerProfile(
        profile: String,
        mode: GeneratedFieldCheckerMode,
        additionalRequired: Set<GeneratedFieldCheckerCoverageSignature>,
    ): Unit =
        runBlocking {
            val effectiveMode = checkerMode(mode)
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ParentFieldsEnabled to true) +
                    (ResolverArgumentErrorWeight to 0.0) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    // Two runtime-bound named pairs create distinct symbolic dependency trees.
                    // Bound their generated depth; nested pairs and provider paths also have
                    // deterministic witnesses, and all activated coverage requirements remain.
                    (ResolverFragmentDepth to if (runtimeFieldCheckerVariables) 1 else 2) +
                    (ResolverQueryFragmentsEnabled to true) +
                    (ResolverQueryFragmentWeight to 1.0) +
                    (ResolverFromArgumentNestedPathWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to runtimeFieldCheckerVariables) +
                    (ResolverFromObjectFieldVariablesEnabled to runtimeFieldCheckerVariables) +
                    (ResolverFromQueryFieldVariablesEnabled to runtimeFieldCheckerVariables) +
                    (ResolverFromProviderVariablesEnabled to runtimeFieldCheckerVariables) +
                    (SometimesPassiveFieldWeight to 0.25) +
                    generatedResolverConfigOverrides
            val assertions =
                generatedCaseAssertions + GeneratedCaseAssertions.exactCheckerApplications
            val required =
                REQUIRED_COVERAGE_SIGNATURES + additionalRequired +
                    if (runtimeFieldCheckerVariables) {
                        setOf(
                            GeneratedFieldCheckerCoverageSignature.FROM_OBJECT_FIELD_VARIABLE,
                            GeneratedFieldCheckerCoverageSignature.FROM_QUERY_FIELD_VARIABLE,
                            GeneratedFieldCheckerCoverageSignature.FROM_PROVIDER_VARIABLE,
                            GeneratedFieldCheckerCoverageSignature.SYMBOLIC_CHECKER_KEY,
                        )
                    } else {
                        emptySet()
                    }
            val property: suspend (GeneratedFieldCheckerCoverage, TestWorld, ResolverTestCase) -> Unit =
                { coverage, testWorld, testCase ->
                    val registry = testCase.registry
                    assertTrue(registry.nodeResolverTypes.isEmpty())
                    assertTrue(registry.generatedFieldCheckerCoordinates.isNotEmpty())
                    val observation =
                        observeGeneratedCaseWithCurrentAssertions(
                            testWorld = testWorld,
                            testCase = testCase,
                            assertions = assertions,
                        )
                    coverage.record(registry, observation)
                }
            val sampledCoverage = GeneratedFieldCheckerCoverage(effectiveMode)
            val run =
                checkGeneratedProfile(
                    profile = checkerProfile(profile),
                    config = config,
                    fieldCheckerMode = effectiveMode,
                ) { testWorld, testCase ->
                    property(sampledCoverage, testWorld, testCase)
                }
            val (coverageRun, requiredCoverage) =
                if (
                    run.selectedCase == null &&
                    run.seed != REQUIRED_COVERAGE_ACTIVATION_SEED &&
                    !sampledCoverage.covers(required)
                ) {
                    val activationCoverage = GeneratedFieldCheckerCoverage(effectiveMode)
                    val activationRun =
                        checkGeneratedProfile(
                            profile = checkerProfile(profile),
                            config = config,
                            seed = REQUIRED_COVERAGE_ACTIVATION_SEED,
                            fieldCheckerMode = effectiveMode,
                        ) { testWorld, testCase ->
                            property(activationCoverage, testWorld, testCase)
                        }
                    activationRun to activationCoverage
                } else {
                    run to sampledCoverage
                }

            requiredCoverage.assertRequired(
                run = coverageRun,
                required = required,
            )
            println(
                "Field-checker coverage profile=${checkerProfile(profile)} " +
                    "seed=${run.seed} ${sampledCoverage.summary()}",
            )
            if (requiredCoverage !== sampledCoverage) {
                println(
                    "Field-checker activation coverage profile=${checkerProfile(profile)} " +
                        "seed=${coverageRun.seed} ${requiredCoverage.summary()}",
                )
            }
        }

    companion object {
        const val SUCCESS_PROFILE = "resolver23-field-checker-success"
        const val DENIAL_PROFILE = "resolver23-field-checker-denial"
        const val MIXED_PROFILE = "resolver23-field-checker-mixed"
        const val PASSIVE_PROFILE = "resolver23-field-checker-passive"
        const val ROOT_REFERENCE_PROFILE = "resolver23-field-checker-root-reference"
        private const val PASSIVE_ACTIVATION_SEED = 1L
        private const val ROOT_REFERENCE_ACTIVATION_SEED = 424242L
        private const val REQUIRED_COVERAGE_ACTIVATION_SEED = 424242L

        private val REQUIRED_COVERAGE_SIGNATURES =
            setOf(
                GeneratedFieldCheckerCoverageSignature.FIELD_CHECKER,
                GeneratedFieldCheckerCoverageSignature.NONEMPTY_OBJECT_FRAGMENT,
                GeneratedFieldCheckerCoverageSignature.NONEMPTY_QUERY_FRAGMENT,
                GeneratedFieldCheckerCoverageSignature.FROM_ARGUMENT_VARIABLE,
                GeneratedFieldCheckerCoverageSignature.NESTED_FROM_ARGUMENT_VARIABLE,
                GeneratedFieldCheckerCoverageSignature.NULLABLE_FROM_ARGUMENT_TRAVERSAL,
                GeneratedFieldCheckerCoverageSignature.PARENT_FIELD_DEMAND,
                GeneratedFieldCheckerCoverageSignature.DUPLICATE_NAMED_PAIR_PROJECTIONS,
                GeneratedFieldCheckerCoverageSignature.EMPTY_NAMED_PAIR,
                GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_OBJECT_DEMAND,
                GeneratedFieldCheckerCoverageSignature.CHECKER_ONLY_QUERY_DEMAND,
                GeneratedFieldCheckerCoverageSignature.SHARED_QUERY_OER_MULTIPLE_OWNERS,
                GeneratedFieldCheckerCoverageSignature.CHECKER_IN_ASSOCIATED_QUERY_OER,
                GeneratedFieldCheckerCoverageSignature.LIST_ELEMENT_OCCURRENCE,
                GeneratedFieldCheckerCoverageSignature.REPEATED_CHECKER_COORDINATE,
                GeneratedFieldCheckerCoverageSignature.ARGUMENT_DISTINCT_OCCURRENCES,
            )
    }
}
