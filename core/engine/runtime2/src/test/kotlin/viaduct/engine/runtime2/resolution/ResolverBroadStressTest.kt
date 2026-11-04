@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.configuredResolverTestExecution
import viaduct.engine.runtime2.arbitrary.resolverTestGenerationConfigured
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.broadStressCases
import viaduct.engine.runtime2.propertytest.configuredResolverTestCaseId

/**
 * Unfiltered Resolution stress: every generated registry/query product is resolved and validated.
 */
@Tag("BazelIgnore")
class ResolverBroadStressTest : ResolutionDispatcherResource {
    @Test
    fun `root field reference focused randomized worlds resolve correctly`(): Unit =
        runBlocking {
            runResolutionBroadStress(
                cases = focusedCases(ResolverTestSuiteDefinition.RESOLUTION_ROOT_FIELD_REFERENCES),
                requiredSignatures = emptySet(),
                resolverCoroutineContext = resolverDispatcher,
            )
        }

    @Test
    fun `parent focused randomized worlds resolve correctly`(): Unit =
        runBlocking {
            runResolutionBroadStress(
                cases = focusedCases(ResolverTestSuiteDefinition.RESOLUTION_PARENT_FIELDS),
                requiredSignatures = setOf(ResolutionStructuralSignature.GREAT_GRANDPARENT_PARENT_DEMAND),
                resolverCoroutineContext = resolverDispatcher,
                parentFocusedReportSlices = 4,
            )
        }

    @Test
    fun `broad full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val broadProfile: ResolutionBroadStressProfile = configuredProfile()
            runResolutionBroadStress(
                cases = broadCases(broadProfile),
                requiredSignatures = broadProfile.requiredSignatures,
                resolverCoroutineContext = resolverDispatcher,
            )
        }

    // The stored suite, unless a configured seed or size asks for live exploration from the suite's own seed and size.
    private fun focusedCases(suite: ResolverTestSuiteDefinition): ResolutionBroadStressCases =
        if (liveGenerationConfigured()) {
            ResolutionBroadStressCases.live(
                profile = suite.profile,
                config = suite.config,
                seed = configuredSeed() ?: suite.seed,
                execution = configuredResolverTestExecution(suite.counts, suite.profile),
            )
        } else {
            suite.broadStressCases()
        }

    // The profile's stored suite, unless a configured seed or size asks for live exploration, which then needs a seed.
    private fun broadCases(broadProfile: ResolutionBroadStressProfile): ResolutionBroadStressCases =
        if (liveGenerationConfigured()) {
            ResolutionBroadStressCases.live(
                profile = broadProfile.propertyProfile,
                config = broadProfile.config,
                seed = configuredSeed() ?: error("Set $SEED_PROPERTY or $SEED_ENVIRONMENT to generate live"),
                execution = configuredResolverTestExecution(configuredCounts(broadProfile), broadProfile.propertyProfile),
            )
        } else {
            ResolverTestSuiteDefinition.forProfile(broadProfile.propertyProfile, broadProfile.config).broadStressCases()
        }

    private fun liveGenerationConfigured(): Boolean =
        resolverTestGenerationConfigured() || listOf(SEED_PROPERTY to SEED_ENVIRONMENT, SIZE_PROPERTY to SIZE_ENVIRONMENT).any { (property, environment) ->
            System.getProperty(property) != null || System.getenv(environment) != null
        }

    // Returns the named generator distribution selected for this run.
    private fun configuredProfile(): ResolutionBroadStressProfile =
        ResolutionBroadStressProfile.configured(
            explicit =
                System.getProperty(PROFILE_PROPERTY)
                    ?: System.getenv(PROFILE_ENVIRONMENT)
                    ?: System.getProperty("resolver.property.profile"),
            selectedSuite = configuredResolverTestCaseId()?.suite,
        )

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

    // Returns the configured seed for live generation, if any.
    private fun configuredSeed(): Long? {
        val configured: String =
            System.getProperty(SEED_PROPERTY)
                ?: System.getenv(SEED_ENVIRONMENT)
                ?: System.getProperty("resolver.property.seed")
                ?: return null
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
