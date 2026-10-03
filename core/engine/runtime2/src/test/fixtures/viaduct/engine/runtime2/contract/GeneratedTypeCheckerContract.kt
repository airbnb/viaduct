@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentCount
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.QueryFieldCount
import viaduct.engine.runtime2.arbitrary.QueryScalarFieldWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentDepth
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromObjectFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromProviderVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableCount
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight

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
