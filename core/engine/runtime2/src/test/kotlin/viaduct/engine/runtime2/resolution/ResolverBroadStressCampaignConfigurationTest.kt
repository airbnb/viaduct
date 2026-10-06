@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.ErrorValueWeight
import viaduct.engine.runtime2.arbitrary.InputScalarValueRange
import viaduct.engine.runtime2.arbitrary.ListTypeWeight
import viaduct.engine.runtime2.arbitrary.ListValueSize
import viaduct.engine.runtime2.arbitrary.MaxSelectionDepth
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.ParentFieldsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldVariableOwnerLimit
import viaduct.engine.runtime2.arbitrary.ResolverFromFieldVariableOwnerUseWeight
import viaduct.engine.runtime2.arbitrary.ResolverFromProviderVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverLiteralVariableConvergenceWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentWeight
import viaduct.engine.runtime2.arbitrary.ResolverQueryFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverTestCaseCoordinate
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.SometimesPassiveFieldWeight
import viaduct.engine.runtime2.propertytest.PropertyTestCampaignConfigFile
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.PropertyTestRoundExecution
import viaduct.engine.runtime2.propertytest.PropertyTestRoundRunner
import viaduct.engine.runtime2.propertytest.roundConfig

class ResolverBroadStressCampaignConfigurationTest {
    @Test
    fun `campaign manifest records one million heterogeneous executions`() {
        val rounds: List<ResolutionBroadStressCampaignRound> =
            ResolutionBroadStressCampaign.rounds
        val runs: List<ResolutionBroadStressCampaignRun> =
            rounds.flatMap(ResolutionBroadStressCampaignRound::runs)

        assertEquals((1..100).toList(), rounds.map { round -> round.number })
        assertEquals(100, rounds.map { round -> round.baseSeed }.toSet().size)
        assertEquals(500, runs.size)
        assertEquals(500, runs.map { run -> run.seed }.toSet().size)
        assertEquals(500, runs.map { run -> run.propertyProfile }.toSet().size)
        assertTrue(runs.all { run -> run.expectedCases == 2_000 })
        assertEquals(1_000_000, runs.sumOf(ResolutionBroadStressCampaignRun::expectedCases))
        assertEquals(
            mapOf(
                ResolutionBroadStressCampaignPhase.SCHEMA_BREADTH to 20,
                ResolutionBroadStressCampaignPhase.REGISTRY_DIVERSITY to 25,
                ResolutionBroadStressCampaignPhase.QUERY_INTERACTIONS to 35,
                ResolutionBroadStressCampaignPhase.LARGE_DEEP to 20,
            ),
            rounds.groupingBy { round -> round.phase }.eachCount(),
        )
        assertTrue(
            rounds
                .filter { round -> round.number > 45 }
                .all { round ->
                    round.runs
                        .single { run ->
                            run.profile == ResolutionBroadStressProfile.MULTIPLE_OWNERS
                        }.counts ==
                        ResolutionBroadStressCampaignPhase.REGISTRY_DIVERSITY.multipleOwnerCounts
                },
        )
    }

    @Test
    fun `broad profile knobs exert distinct Resolution pressure`() {
        val balanced = ResolutionBroadStressProfile.BALANCED.config
        val descendants = ResolutionBroadStressProfile.DESCENDANT_VARIABLES.config
        val nullableErrors = ResolutionBroadStressProfile.NULLABLE_ERRORS.config
        val symbolicIdentity = ResolutionBroadStressProfile.SYMBOLIC_IDENTITY.config
        val multipleOwners = ResolutionBroadStressProfile.MULTIPLE_OWNERS.config

        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[SometimesPassiveFieldWeight] == 0.25
            },
        )
        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[ResolverQueryFragmentsEnabled]
            },
        )
        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[ResolverFromQueryFieldVariablesEnabled]
            },
        )
        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[ResolverFromProviderVariablesEnabled]
            },
        )
        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[ResolverQueryFragmentWeight] == 0.1
            },
        )
        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[ParentFieldsEnabled]
            },
        )
        assertTrue(
            ResolutionBroadStressProfile.entries.all { profile ->
                profile.config[MaxSelectionDepth] >= 6
            },
        )

        assertTrue(descendants[ListTypeWeight] > balanced[ListTypeWeight])
        assertEquals(1..2, descendants[ListValueSize])
        assertEquals(
            "40:25:10",
            ResolutionBroadStressProfile.DESCENDANT_VARIABLES.defaultSize,
        )
        assertTrue(nullableErrors[ErrorValueWeight] > balanced[ErrorValueWeight])
        assertTrue(
            symbolicIdentity[ResolverLiteralVariableConvergenceWeight] >
                balanced[ResolverLiteralVariableConvergenceWeight],
        )
        assertEquals(0..1, symbolicIdentity[InputScalarValueRange])
        assertTrue(
            multipleOwners[ResolverFromFieldVariableOwnerUseWeight] >
                balanced[ResolverFromFieldVariableOwnerUseWeight],
        )
        assertEquals(4, multipleOwners[ResolverFromFieldVariableOwnerLimit])

        val largeDeep = balanced.withLargeDeepResolutionWorlds()
        assertEquals(8..12, largeDeep[SchemaObjectCount])
        assertEquals(6..10, largeDeep[ObjectFieldCount])
        assertEquals(6, largeDeep[MaxSelectionDepth])
        assertEquals(
            balanced[DuplicateSelectionWeight],
            largeDeep[DuplicateSelectionWeight],
        )
        assertEquals(1..1, largeDeep[ListValueSize])
    }

    @Test
    fun `campaign records the large deep duplicate-selection cap transition`() {
        val round81Runs: List<ResolutionBroadStressCampaignRun> =
            ResolutionBroadStressCampaign.round(81).runs
        val round95Runs: List<ResolutionBroadStressCampaignRun> =
            ResolutionBroadStressCampaign.round(95).runs

        assertEquals(
            0.1,
            round81Runs
                .single { run ->
                    run.profile == ResolutionBroadStressProfile.SYMBOLIC_IDENTITY
                }.config[DuplicateSelectionWeight],
        )
        assertEquals(
            0.2,
            round81Runs
                .single { run ->
                    run.profile == ResolutionBroadStressProfile.BALANCED
                }.config[DuplicateSelectionWeight],
        )
        assertTrue(
            round95Runs.all { run ->
                run.config[DuplicateSelectionWeight] == 0.1
            },
        )
    }

    @Test
    fun `query interaction rounds retain diversity for registry-shaped profiles`() {
        val queryInteractionRound: ResolutionBroadStressCampaignRound =
            ResolutionBroadStressCampaign.round(46)
        val registryShapedProfiles: Set<ResolutionBroadStressProfile> =
            setOf(
                ResolutionBroadStressProfile.DESCENDANT_VARIABLES,
                ResolutionBroadStressProfile.NULLABLE_ERRORS,
                ResolutionBroadStressProfile.MULTIPLE_OWNERS,
            )
        val registryShapedRuns: List<ResolutionBroadStressCampaignRun> =
            queryInteractionRound.runs.filter { run ->
                run.profile in registryShapedProfiles
            }

        assertTrue(
            registryShapedRuns.all { run ->
                run.counts ==
                    ResolutionBroadStressCampaignPhase.REGISTRY_DIVERSITY.commonCounts
            },
        )
        assertTrue(registryShapedRuns.all { run -> run.expectedCases == 2_000 })
    }

    @Test
    fun `persisted campaign coordinate executes Resolution validation oracles`() =
        runBlocking {
            val campaign =
                PropertyTestJson.readResource<PropertyTestCampaignConfigFile>(
                    "/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json",
                )
            val profile = ResolutionBroadStressProfile.BALANCED
            val round = campaign.roundConfig(number = 1, selectedProfileId = profile.id)
            val run = round.runs.single()

            val result =
                PropertyTestRoundRunner.run(
                    round = round,
                    execution =
                        PropertyTestRoundExecution(
                            selectedTestInputProfileId = run.testInputProfileId,
                            selectedCase =
                                ResolverTestCaseCoordinate(
                                    schemaIndex = 1,
                                    registryIndex = 1,
                                    queryIndex = 1,
                                ),
                        ),
                )

            assertEquals(1, result.completedCases)
        }
}
