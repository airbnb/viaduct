package viaduct.engine.runtime2.resolution

import java.io.InputStream
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.TestCaseCount

// Describes one fresh-JVM campaign round and derives its five profile runs.
internal data class ResolutionBroadStressCampaignRound(
    val number: Int,
    val baseSeed: Long,
    val phase: ResolutionBroadStressCampaignPhase,
) {
    val runs: List<ResolutionBroadStressCampaignRun>
        get() =
            ResolutionBroadStressProfile.entries.mapIndexed { index, profile ->
                ResolutionBroadStressCampaignRun(
                    round = number,
                    phase = phase,
                    profile = profile,
                    propertyProfile =
                        "resolution-broad-campaign-v1-r${number.toString().padStart(3, '0')}-" +
                            profile.id,
                    counts = phase.countsFor(profile),
                    config = configFor(profile),
                    seed = Math.addExact(Math.multiplyExact(baseSeed, 10), index.toLong() + 1),
                )
            }

    // Reconstructs the exact profile configuration used when this campaign round ran.
    private fun configFor(profile: ResolutionBroadStressProfile): Config {
        val config: Config =
            if (phase.largeDeep) {
                profile.config.withLargeDeepResolutionWorlds()
            } else {
                profile.config
            }
        return if (
            phase.largeDeep &&
            (profile == ResolutionBroadStressProfile.SYMBOLIC_IDENTITY || number >= 95)
        ) {
            config + (DuplicateSelectionWeight to 0.1)
        } else {
            config
        }
    }
}

// Holds one reproducible profile execution within a campaign round.
internal data class ResolutionBroadStressCampaignRun(
    val round: Int,
    val phase: ResolutionBroadStressCampaignPhase,
    val profile: ResolutionBroadStressProfile,
    val propertyProfile: String,
    val counts: TestCaseCount,
    val config: Config,
    val seed: Long,
) {
    val expectedCases: Int =
        counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
}

// Varies which generator dimension receives most of a round's sampling budget.
internal enum class ResolutionBroadStressCampaignPhase(
    val id: String,
    val commonCounts: TestCaseCount,
    val multipleOwnerCounts: TestCaseCount,
    val largeDeep: Boolean,
) {
    SCHEMA_BREADTH(
        id = "schema-breadth",
        commonCounts = TestCaseCount(200, 2, 5),
        multipleOwnerCounts = TestCaseCount(200, 2, 5),
        largeDeep = false,
    ),
    REGISTRY_DIVERSITY(
        id = "registry-diversity",
        commonCounts = TestCaseCount(40, 25, 2),
        multipleOwnerCounts = TestCaseCount(40, 25, 2),
        largeDeep = false,
    ),
    QUERY_INTERACTIONS(
        id = "query-interactions",
        commonCounts = TestCaseCount(10, 4, 50),
        multipleOwnerCounts = TestCaseCount(40, 25, 2),
        largeDeep = false,
    ),
    LARGE_DEEP(
        id = "large-deep",
        commonCounts = TestCaseCount(20, 10, 10),
        multipleOwnerCounts = TestCaseCount(40, 25, 2),
        largeDeep = true,
    ),
    ;

    companion object {
        // Returns the campaign phase with the persisted manifest id.
        fun fromId(id: String): ResolutionBroadStressCampaignPhase =
            entries.singleOrNull { phase -> phase.id == id }
                ?: error(
                    "Unknown Resolution broad stress campaign phase $id; phases=" +
                        entries.joinToString { phase -> phase.id },
                )
    }
}

// Returns sampling dimensions that retain registry diversity for registry-shape profiles.
private fun ResolutionBroadStressCampaignPhase.countsFor(profile: ResolutionBroadStressProfile): TestCaseCount =
    when {
        profile == ResolutionBroadStressProfile.MULTIPLE_OWNERS -> multipleOwnerCounts
        this == ResolutionBroadStressCampaignPhase.QUERY_INTERACTIONS &&
            profile in
            setOf(
                ResolutionBroadStressProfile.DESCENDANT_VARIABLES,
                ResolutionBroadStressProfile.NULLABLE_ERRORS,
            ) ->
            ResolutionBroadStressCampaignPhase.REGISTRY_DIVERSITY.commonCounts
        else -> commonCounts
    }

// Loads the checked-in campaign seeds and supports exact round lookup.
internal object ResolutionBroadStressCampaign {
    const val ROUND_PROPERTY = "resolution.broad.campaign.round"
    const val PROFILE_PROPERTY = "resolution.broad.campaign.profile"
    const val MANIFEST_RESOURCE =
        "/viaduct/engine/runtime2/resolution/resolution-broad-stress-campaign.tsv"

    val rounds: List<ResolutionBroadStressCampaignRound> by lazy {
        val stream: InputStream =
            requireNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
                "Missing Resolution broad stress campaign manifest $MANIFEST_RESOURCE"
            }
        stream.bufferedReader().useLines { lines ->
            lines
                .filterNot { line -> line.isBlank() || line.startsWith("#") }
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) {
                        "Campaign rows must have round, seed, and phase columns: $line"
                    }
                    ResolutionBroadStressCampaignRound(
                        number =
                            columns[0].toIntOrNull()
                                ?: error("Campaign round must be an integer: $line"),
                        baseSeed =
                            columns[1].toLongOrNull()
                                ?: error("Campaign seed must be a Long: $line"),
                        phase = ResolutionBroadStressCampaignPhase.fromId(columns[2]),
                    )
                }.toList()
        }
    }

    // Returns exactly one persisted campaign round.
    fun round(number: Int): ResolutionBroadStressCampaignRound =
        rounds.singleOrNull { round -> round.number == number }
            ?: error("Unknown Resolution broad stress campaign round $number")
}
