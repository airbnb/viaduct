@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import viaduct.engine.runtime2.arbitrary.parseResolverTestCase
import viaduct.engine.runtime2.propertytest.PropertyTestCampaignConfigFile
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.PropertyTestRoundExecution
import viaduct.engine.runtime2.propertytest.PropertyTestRoundRunner
import viaduct.engine.runtime2.propertytest.configuredResolverTestCaseId
import viaduct.engine.runtime2.propertytest.roundConfig

@Tag("BazelIgnore")
class ResolverBroadStressCampaignTest {
    @Test
    fun `campaign round resolves every case in five broad profiles`(): Unit =
        runBlocking {
            // A selected test case without a configured round replays the stored campaign run that holds it.
            val selectedTestCase = configuredResolverTestCaseId()
            val selectedRun = selectedTestCase?.let { selected -> ResolutionBroadStressCampaign.runForSuite(selected.suite) }
            val roundNumber =
                configuredRound()
                    ?: selectedRun?.round
                    // A test case from another suite runs nothing here, like every other suite that does not hold it.
                    ?: if (selectedTestCase != null) return@runBlocking else error("Set ${ResolutionBroadStressCampaign.ROUND_PROPERTY} to a manifest round")
            val selectedProfile: ResolutionBroadStressProfile? = configuredProfile() ?: selectedRun?.profile
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
                        selectedTestCase = selectedTestCase,
                    ),
            )
        }

    // Returns the configured persisted round number, if any.
    private fun configuredRound(): Int? =
        System
            .getProperty(ResolutionBroadStressCampaign.ROUND_PROPERTY)
            ?.let { configured -> configured.toIntOrNull() ?: error("${ResolutionBroadStressCampaign.ROUND_PROPERTY} must be an integer: $configured") }

    // Returns an optional single profile for focused replay.
    private fun configuredProfile(): ResolutionBroadStressProfile? =
        System
            .getProperty(ResolutionBroadStressCampaign.PROFILE_PROPERTY)
            ?.let(ResolutionBroadStressProfile::fromConfigured)

    private companion object {
        const val PROFILE_PROPERTY = ResolutionBroadStressCampaign.PROFILE_PROPERTY
    }
}
