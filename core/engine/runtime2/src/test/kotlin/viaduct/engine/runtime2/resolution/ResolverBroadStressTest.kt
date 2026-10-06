@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ErrorValueWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.ListValueSize
import viaduct.engine.runtime2.arbitrary.MaxOutputListDepth
import viaduct.engine.runtime2.arbitrary.MaxSelectionDepth
import viaduct.engine.runtime2.arbitrary.MinimumSelectionDepth
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.NullValueWeight
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.RandomParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFragmentArgumentFieldWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentDepth
import viaduct.engine.runtime2.arbitrary.ResolverFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldProviderPathLength
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldVariableUseDepth
import viaduct.engine.runtime2.arbitrary.ResolverFromObjectFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableCount
import viaduct.engine.runtime2.arbitrary.ResolverVariableSingletonCoercionEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.RootFieldReferenceWeight
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.configuredResolverTestExecution

/**
 * Unfiltered Resolution stress: every generated registry/query product is resolved and validated.
 */
class ResolverBroadStressTest : ResolutionDispatcherResource {
    @Test
    fun `root field reference focused randomized worlds resolve correctly`(): Unit =
        runBlocking {
            val defaultCounts =
                TestCaseCount(schemas = 10, registriesPerSchema = 5, queriesPerSchema = 5)
            val propertyProfile = "resolution-root-field-references"
            val execution = configuredResolverTestExecution(defaultCounts, propertyProfile)
            val counts = execution.counts
            val completed =
                runResolutionBroadStress(
                    resolverCoroutineContext = resolverDispatcher,
                    requiredSignatures = emptySet(),
                    propertyProfile = propertyProfile,
                    counts = counts,
                    config =
                        ResolutionBroadStressProfile.BALANCED.config +
                            (RootFieldReferenceWeight to 0.6),
                    seed = configuredSeed(default = 2026091001L),
                    execution = execution,
                )

            assertEquals(
                if (execution.selectedCase == null) {
                    counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
                } else {
                    1
                },
                completed,
            )
        }

    @Test
    fun `parent focused randomized worlds resolve correctly`(): Unit =
        runBlocking {
            val defaultCounts =
                TestCaseCount(schemas = 40, registriesPerSchema = 5, queriesPerSchema = 5)
            val propertyProfile = "resolution-parent-fields"
            val execution = configuredResolverTestExecution(defaultCounts, propertyProfile)
            val counts = execution.counts
            val completed =
                runResolutionBroadStress(
                    resolverCoroutineContext = resolverDispatcher,
                    requiredSignatures =
                        setOf(
                            ResolutionStructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND,
                        ),
                    propertyProfile = propertyProfile,
                    counts = counts,
                    config =
                        Config.default +
                            (ParentFieldsEnabled to true) +
                            (MinimumSelectionDepth to 2) +
                            (MaxSelectionDepth to 6) +
                            (RandomParentFieldsEnabled to true) +
                            (ResolverFragmentsEnabled to true) +
                            (ResolverFragmentWeight to 1.0) +
                            (ResolverFragmentDepth to 3) +
                            (FieldArgumentWeight to 0.65) +
                            (ResolverFragmentArgumentFieldWeight to 1.0) +
                            (ResolverQueryFragmentsEnabled to true) +
                            (ResolverQueryFragmentWeight to 0.25) +
                            (ResolverVariablesEnabled to true) +
                            (ResolverFromArgumentVariablesEnabled to true) +
                            (ResolverFromObjectFieldVariablesEnabled to true) +
                            (ResolverFromQueryFieldVariablesEnabled to true) +
                            (ResolverVariableWeight to 1.0) +
                            (ResolverVariableCount to 2..3) +
                            (ResolverVariableSingletonCoercionEnabled to true) +
                            (ResolverFromFieldProviderPathLength to 1..3) +
                            (ResolverFromFieldVariableUseDepth to 1..3) +
                            (SometimesPassiveFieldWeight to 1.0) +
                            (NodeResolversEnabled to false) +
                            (MaxOutputListDepth to 2) +
                            (ListValueSize to 1..1) +
                            (NullValueWeight to 0.0) +
                            (ErrorValueWeight to 0.0),
                    seed = configuredSeed(default = 2026090403L),
                    execution = execution,
                    parentFocusedReportSlices = if (execution.selectedCase == null) 4 else 1,
                )

            assertEquals(
                if (execution.selectedCase == null) {
                    counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
                } else {
                    1
                },
                completed,
            )
        }

    @Test
    fun `broad full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val broadProfile: ResolutionBroadStressProfile = configuredProfile()
            runResolutionBroadStress(
                resolverCoroutineContext = resolverDispatcher,
                requiredSignatures = broadProfile.requiredSignatures,
                propertyProfile = broadProfile.propertyProfile,
                counts = configuredCounts(broadProfile),
                config = broadProfile.config,
                seed = configuredSeed(),
            )
        }

    // Returns the named generator distribution selected for this run.
    private fun configuredProfile(): ResolutionBroadStressProfile {
        val configured: String =
            System.getProperty(PROFILE_PROPERTY)
                ?: System.getenv(PROFILE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.profile")
                ?: ResolutionBroadStressProfile.BALANCED.propertyProfile
        return ResolutionBroadStressProfile.fromConfigured(configured)
    }

    // Returns the configured S:R:Q product dimensions.
    private fun configuredCounts(broadProfile: ResolutionBroadStressProfile): TestCaseCount {
        val configured: String =
            System.getProperty(SIZE_PROPERTY)
                ?: System.getenv(SIZE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.size")
                ?: broadProfile.defaultSize
        val dimensions: List<String> = configured.split(':')
        require(dimensions.size == 3) {
            "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have S:R:Q form: $configured"
        }
        val parsed: List<Int> =
            dimensions.map { dimension ->
                dimension.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: error(
                        "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have positive dimensions: " +
                            configured,
                    )
            }
        return TestCaseCount(
            schemas = parsed[0],
            registriesPerSchema = parsed[1],
            queriesPerSchema = parsed[2],
        )
    }

    // Returns the explicit seed required for reproducible broad generation.
    private fun configuredSeed(default: Long? = null): Long {
        val configured: String =
            System.getProperty(SEED_PROPERTY)
                ?: System.getenv(SEED_ENVIRONMENT)
                ?: System.getProperty("resolver.property.seed")
                ?: default?.toString()
                ?: error(
                    "Set $SEED_PROPERTY or $SEED_ENVIRONMENT; use the " +
                        ":semantics:resolutionBroadStress task",
                )
        return configured.toLongOrNull()
            ?: error("$SEED_PROPERTY/$SEED_ENVIRONMENT must be a Long: $configured")
    }

    private companion object {
        const val PROFILE_PROPERTY = "resolution.broad.stress.profile"
        const val PROFILE_ENVIRONMENT = "RESOLUTION_BROAD_STRESS_PROFILE"
        const val SIZE_PROPERTY = "resolution.broad.stress.size"
        const val SIZE_ENVIRONMENT = "RESOLUTION_BROAD_STRESS_SIZE"
        const val SEED_PROPERTY = "resolution.broad.stress.seed"
        const val SEED_ENVIRONMENT = "RESOLUTION_BROAD_STRESS_SEED"
    }
}
