@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import viaduct.engine.runtime2.arbitrary.parseResolverTestCase
import viaduct.engine.runtime2.propertytest.PropertyTestCampaignConfigFile
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.PropertyTestRoundExecution
import viaduct.engine.runtime2.propertytest.PropertyTestRoundRunner
import viaduct.engine.runtime2.propertytest.roundConfig

class ResolverBroadStressCampaignTest {
    @Test
    fun `campaign round resolves every case in five broad profiles`(): Unit =
        runBlocking {
            val roundNumber = configuredRound()
            val selectedProfile: ResolutionBroadStressProfile? = configuredProfile()
            val campaign =
                PropertyTestJson.readResource<PropertyTestCampaignConfigFile>(
                    "/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json",
                )
            val round = campaign.roundConfig(roundNumber, selectedProfile?.id)
            val configuredCase: String? = System.getProperty(RESOLVER_TEST_CASE_PROPERTY)
            require(
                configuredCase == null ||
                    configuredCase.equals("all", ignoreCase = true) ||
                    selectedProfile != null,
            ) {
                "A campaign coordinate replay must select $PROFILE_PROPERTY"
            }
            PropertyTestRoundRunner.run(
                round = round,
                execution =
                    PropertyTestRoundExecution(
                        selectedTestInputProfileId =
                            selectedProfile?.let { round.runs.single().testInputProfileId },
                        selectedCase =
                            configuredCase
                                ?.takeUnless { value -> value.equals("all", ignoreCase = true) }
                                ?.let(::parseResolverTestCase),
                    ),
            )
        }

    // Returns the required persisted round number.
    private fun configuredRound(): Int =
        System
            .getProperty(ResolutionBroadStressCampaign.ROUND_PROPERTY)
            ?.toIntOrNull()
            ?: error("Set ${ResolutionBroadStressCampaign.ROUND_PROPERTY} to a manifest round")

    // Returns an optional single profile for focused replay.
    private fun configuredProfile(): ResolutionBroadStressProfile? =
        System
            .getProperty(ResolutionBroadStressCampaign.PROFILE_PROPERTY)
            ?.let(ResolutionBroadStressProfile::fromConfigured)

    private companion object {
        const val PROFILE_PROPERTY = ResolutionBroadStressCampaign.PROFILE_PROPERTY
    }
}
