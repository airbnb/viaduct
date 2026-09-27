package semantics.contract

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.GeneratedFieldCheckerMode
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.ResolverArgumentErrorWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFromArgumentNestedPathWeight
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverQueryFragmentWeight
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.RootFieldReferencesEnabled
import semantics.arbitrary.RootFieldReferenceWeight
import semantics.arbitrary.SometimesPassiveFieldWeight
import kotlin.test.assertTrue

/** Resolver23 generated coverage for grounded field checkers and exact applications. */
interface GeneratedFieldCheckerContract : GeneratedCaseAssertionPolicy {
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
            val coverage = GeneratedFieldCheckerCoverage(GeneratedFieldCheckerMode.SUCCESS)
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
                generatedCaseAssertions + GeneratedCaseAssertions.exactFieldCheckerApplications
            val run =
                checkGeneratedProfile(
                    profile = PASSIVE_PROFILE,
                    config = config,
                    seed = PASSIVE_ACTIVATION_SEED,
                    fieldCheckerMode = GeneratedFieldCheckerMode.SUCCESS,
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
            println("Resolver23 field-checker coverage profile=$PASSIVE_PROFILE ${coverage.summary()}")
        }

    @Test
    fun `generated root-reference field checker worlds resolve correctly`(): Unit =
        runBlocking {
            val coverage = GeneratedFieldCheckerCoverage(GeneratedFieldCheckerMode.SUCCESS)
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
                generatedCaseAssertions + GeneratedCaseAssertions.exactFieldCheckerApplications
            val run =
                checkGeneratedProfile(
                    profile = ROOT_REFERENCE_PROFILE,
                    config = config,
                    seed = ROOT_REFERENCE_ACTIVATION_SEED,
                    fieldCheckerMode = GeneratedFieldCheckerMode.SUCCESS,
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
                "Resolver23 field-checker coverage profile=$ROOT_REFERENCE_PROFILE " +
                    coverage.summary(),
            )
        }

    private fun runGeneratedFieldCheckerProfile(
        profile: String,
        mode: GeneratedFieldCheckerMode,
        additionalRequired: Set<GeneratedFieldCheckerCoverageSignature>,
    ): Unit =
        runBlocking {
            val coverage = GeneratedFieldCheckerCoverage(mode)
            val config =
                Config.default +
                    (FieldArgumentWeight to 1.0) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (NodeResolversEnabled to false) +
                    (ParentFieldsEnabled to true) +
                    (ResolverArgumentErrorWeight to 0.0) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (ResolverQueryFragmentsEnabled to true) +
                    (ResolverQueryFragmentWeight to 1.0) +
                    (ResolverFromArgumentNestedPathWeight to 1.0) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (ResolverVariablesEnabled to false) +
                    (SometimesPassiveFieldWeight to 0.25) +
                    generatedResolverConfigOverrides
            val assertions =
                generatedCaseAssertions + GeneratedCaseAssertions.exactFieldCheckerApplications

            val run =
                checkGeneratedProfile(
                    profile = profile,
                    config = config,
                    fieldCheckerMode = mode,
                ) { testWorld, testCase ->
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

            coverage.assertRequired(
                run = run,
                required = REQUIRED_COVERAGE_SIGNATURES + additionalRequired,
            )
            println("Resolver23 field-checker coverage profile=$profile ${coverage.summary()}")
        }

    companion object {
        const val SUCCESS_PROFILE = "resolver23-field-checker-success"
        const val DENIAL_PROFILE = "resolver23-field-checker-denial"
        const val MIXED_PROFILE = "resolver23-field-checker-mixed"
        const val PASSIVE_PROFILE = "resolver23-field-checker-passive"
        const val ROOT_REFERENCE_PROFILE = "resolver23-field-checker-root-reference"
        private const val PASSIVE_ACTIVATION_SEED = 1L
        private const val ROOT_REFERENCE_ACTIVATION_SEED = 424242L

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
