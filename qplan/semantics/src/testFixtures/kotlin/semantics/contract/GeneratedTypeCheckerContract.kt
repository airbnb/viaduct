@file:Suppress("ForbiddenImport")

package semantics.contract

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.GeneratedFieldCheckerMode
import semantics.arbitrary.GeneratedTypeCheckerMode
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.QueryScalarFieldWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.SometimesPassiveFieldWeight

/** Replayable type-check profiles layered on randomized schemas, registries, values, and queries. */
interface GeneratedTypeCheckerContract : GeneratedCaseAssertionPolicy {
    @Test
    fun `generated successful type checker worlds resolve correctly`() = typeCheckerProfile(GeneratedTypeCheckerMode.SUCCESS)

    @Test
    fun `generated denying type checker worlds resolve correctly`() = typeCheckerProfile(GeneratedTypeCheckerMode.DENIAL)

    @Test
    fun `generated mixed type checker worlds resolve correctly`() = typeCheckerProfile(GeneratedTypeCheckerMode.MIXED)

    private fun typeCheckerProfile(mode: GeneratedTypeCheckerMode): Unit =
        runBlocking {
            val coverage = GeneratedTypeCheckerCoverage()
            val profile = "resolver23-type-checker-${mode.name.lowercase()}"
            val run = checkGeneratedProfile(
                profile = profile,
                config = Config.default +
                    (SchemaObjectCount to 4..6) +
                    (ObjectFieldCount to 4..6) +
                    (QueryFieldCount to 6..8) +
                    (QueryScalarFieldWeight to 0.35) +
                    (ExplicitFieldResolverWeight to 0.65) +
                    (ResolverFragmentWeight to 0.8) +
                    (NodeResolversEnabled to false) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentDepth to 1) +
                    (ResolverQueryFragmentsEnabled to true) +
                    (ResolverVariablesEnabled to false) +
                    (ResolverFromArgumentVariablesEnabled to false) +
                    (SometimesPassiveFieldWeight to 0.25) + generatedResolverConfigOverrides,
                fieldCheckerMode = GeneratedFieldCheckerMode.MIXED,
                typeCheckerMode = mode,
            ) { world, testCase ->
                val observation = observeGeneratedCaseWithCurrentAssertions(
                    testWorld = world,
                    testCase = testCase,
                    assertions = generatedCaseAssertions + GeneratedCaseAssertions.exactCheckerApplications,
                )
                coverage.record(observation.ordinary)
            }
            coverage.assertRequired(run, mode)
            println("Type-checker coverage profile=$profile seed=${run.seed} cases=${run.attemptedCases} ${coverage.summary()}")
        }
}
