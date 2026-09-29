@file:Suppress("ForbiddenImport")

package semantics.resolver26

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import semantics.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import semantics.arbitrary.parseResolverTestCase
import semantics.propertytest.PropertyTestCampaignConfigFile
import semantics.propertytest.PropertyTestJson
import semantics.propertytest.PropertyTestRoundExecution
import semantics.propertytest.PropertyTestRoundRunner
import semantics.propertytest.roundConfig

class ResolverBroadStressCampaignTest {
    @Test
    fun `campaign round resolves every case in five broad profiles`(): Unit =
        runBlocking {
            val roundNumber = configuredRound()
            val selectedProfile: Resolver26BroadStressProfile? = configuredProfile()
            val campaign =
                PropertyTestJson.readResource<PropertyTestCampaignConfigFile>(
                    "/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json",
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
            .getProperty(Resolver26BroadStressCampaign.ROUND_PROPERTY)
            ?.toIntOrNull()
            ?: error("Set ${Resolver26BroadStressCampaign.ROUND_PROPERTY} to a manifest round")

    // Returns an optional single profile for focused replay.
    private fun configuredProfile(): Resolver26BroadStressProfile? =
        System
            .getProperty(Resolver26BroadStressCampaign.PROFILE_PROPERTY)
            ?.let(Resolver26BroadStressProfile::fromConfigured)

    private companion object {
        const val PROFILE_PROPERTY = Resolver26BroadStressCampaign.PROFILE_PROPERTY
    }
}
