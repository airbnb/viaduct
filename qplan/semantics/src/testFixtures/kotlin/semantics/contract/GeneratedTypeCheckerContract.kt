@file:Suppress("ForbiddenImport")

package semantics.contract

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentCount
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
import semantics.arbitrary.ResolverFromObjectFieldVariablesEnabled
import semantics.arbitrary.ResolverFromProviderVariablesEnabled
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.SometimesPassiveFieldWeight

/** Replayable type-check profiles layered on randomized schemas, registries, values, and queries. */
interface GeneratedTypeCheckerContract : GeneratedCaseAssertionPolicy {
    val typeCheckerProfilePrefix: String get() = "resolver23"
    val runtimeTypeCheckerVariables: Boolean get() = false

    val typeCheckerConfig: Config
        get() = Config.default +
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
            (ResolverVariablesEnabled to runtimeTypeCheckerVariables) +
            (ResolverFromArgumentVariablesEnabled to false) +
            (ResolverFromProviderVariablesEnabled to runtimeTypeCheckerVariables) +
            (ResolverFromObjectFieldVariablesEnabled to runtimeTypeCheckerVariables) +
            (ResolverFromQueryFieldVariablesEnabled to runtimeTypeCheckerVariables) +
            // Callback assignment precedes path assignment. Leave argument positions for both.
            (FieldArgumentCount to if (runtimeTypeCheckerVariables) 2..3 else FieldArgumentCount.default) +
            (ResolverVariableCount to if (runtimeTypeCheckerVariables) 1..1 else ResolverVariableCount.default) +
            (ResolverVariableWeight to 1.0) +
            (SometimesPassiveFieldWeight to 0.25) + generatedResolverConfigOverrides

    fun typeCheckerMode(mode: GeneratedTypeCheckerMode): GeneratedTypeCheckerMode =
        if (runtimeTypeCheckerVariables) {
            when (mode) {
                GeneratedTypeCheckerMode.SUCCESS -> GeneratedTypeCheckerMode.RUNTIME_SUCCESS
                GeneratedTypeCheckerMode.DENIAL -> GeneratedTypeCheckerMode.RUNTIME_DENIAL
                GeneratedTypeCheckerMode.MIXED -> GeneratedTypeCheckerMode.RUNTIME_MIXED
                else -> error("Not a grounded checker mode: $mode")
            }
        } else {
            mode
        }

    @Test
    fun `generated successful type checker worlds resolve correctly`() = typeCheckerProfile(GeneratedTypeCheckerMode.SUCCESS)

    @Test
    fun `generated denying type checker worlds resolve correctly`() = typeCheckerProfile(GeneratedTypeCheckerMode.DENIAL)

    @Test
    fun `generated mixed type checker worlds resolve correctly`() = typeCheckerProfile(GeneratedTypeCheckerMode.MIXED)

    private fun typeCheckerProfile(mode: GeneratedTypeCheckerMode): Unit =
        runBlocking {
            val coverage = GeneratedTypeCheckerCoverage()
            val profile = "$typeCheckerProfilePrefix-type-checker-${mode.name.lowercase()}"
            val effectiveMode = typeCheckerMode(mode)
            val run = checkGeneratedProfile(
                profile = profile,
                config = typeCheckerConfig,
                fieldCheckerMode = if (runtimeTypeCheckerVariables) GeneratedFieldCheckerMode.RUNTIME_MIXED else GeneratedFieldCheckerMode.MIXED,
                typeCheckerMode = effectiveMode,
            ) { world, testCase ->
                val observation = observeGeneratedCaseWithCurrentAssertions(
                    testWorld = world,
                    testCase = testCase,
                    assertions = generatedCaseAssertions + GeneratedCaseAssertions.exactCheckerApplications,
                )
                coverage.record(observation.ordinary)
            }
            coverage.assertRequired(run, effectiveMode)
            println("Type-checker coverage profile=$profile seed=${run.seed} cases=${run.attemptedCases} ${coverage.summary()}")
        }
}
