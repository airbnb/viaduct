package semantics.resolver26

import java.io.InputStream
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.TestCaseCount

// Describes one fresh-JVM campaign round and derives its five profile runs.
internal data class Resolver26BroadStressCampaignRound(
    val number: Int,
    val baseSeed: Long,
    val phase: Resolver26BroadStressCampaignPhase,
) {
    val runs: List<Resolver26BroadStressCampaignRun>
        get() =
            Resolver26BroadStressProfile.entries.mapIndexed { index, profile ->
                Resolver26BroadStressCampaignRun(
                    round = number,
                    phase = phase,
                    profile = profile,
                    propertyProfile =
                        "resolver26-broad-campaign-v1-r${number.toString().padStart(3, '0')}-" +
                            profile.id,
                    counts = phase.countsFor(profile),
                    config = configFor(profile),
                    seed = Math.addExact(Math.multiplyExact(baseSeed, 10), index.toLong() + 1),
                )
            }

    // Reconstructs the exact profile configuration used when this campaign round ran.
    private fun configFor(profile: Resolver26BroadStressProfile): Config {
        val config: Config =
            if (phase.largeDeep) {
                profile.config.withLargeDeepResolver26Worlds()
            } else {
                profile.config
            }
        return if (
            phase.largeDeep &&
            (profile == Resolver26BroadStressProfile.SYMBOLIC_IDENTITY || number >= 95)
        ) {
            config + (DuplicateSelectionWeight to 0.1)
        } else {
            config
        }
    }
}

// Holds one reproducible profile execution within a campaign round.
internal data class Resolver26BroadStressCampaignRun(
    val round: Int,
    val phase: Resolver26BroadStressCampaignPhase,
    val profile: Resolver26BroadStressProfile,
    val propertyProfile: String,
    val counts: TestCaseCount,
    val config: Config,
    val seed: Long,
) {
    val expectedCases: Int =
        counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
}

// Varies which generator dimension receives most of a round's sampling budget.
internal enum class Resolver26BroadStressCampaignPhase(
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
        fun fromId(id: String): Resolver26BroadStressCampaignPhase =
            entries.singleOrNull { phase -> phase.id == id }
                ?: error(
                    "Unknown Resolver26 broad stress campaign phase $id; phases=" +
                        entries.joinToString { phase -> phase.id },
                )
    }
}

// Returns sampling dimensions that retain registry diversity for registry-shape profiles.
private fun Resolver26BroadStressCampaignPhase.countsFor(profile: Resolver26BroadStressProfile): TestCaseCount =
    when {
        profile == Resolver26BroadStressProfile.MULTIPLE_OWNERS -> multipleOwnerCounts
        this == Resolver26BroadStressCampaignPhase.QUERY_INTERACTIONS &&
            profile in
            setOf(
                Resolver26BroadStressProfile.DESCENDANT_VARIABLES,
                Resolver26BroadStressProfile.NULLABLE_ERRORS,
            ) ->
            Resolver26BroadStressCampaignPhase.REGISTRY_DIVERSITY.commonCounts
        else -> commonCounts
    }

// Loads the checked-in campaign seeds and supports exact round lookup.
internal object Resolver26BroadStressCampaign {
    const val ROUND_PROPERTY = "resolver26.broad.campaign.round"
    const val PROFILE_PROPERTY = "resolver26.broad.campaign.profile"
    const val MANIFEST_RESOURCE =
        "/semantics/resolver26/resolver26-broad-stress-campaign.tsv"

    val rounds: List<Resolver26BroadStressCampaignRound> by lazy {
        val stream: InputStream =
            requireNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
                "Missing Resolver26 broad stress campaign manifest $MANIFEST_RESOURCE"
            }
        stream.bufferedReader().useLines { lines ->
            lines
                .filterNot { line -> line.isBlank() || line.startsWith("#") }
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) {
                        "Campaign rows must have round, seed, and phase columns: $line"
                    }
                    Resolver26BroadStressCampaignRound(
                        number =
                            columns[0].toIntOrNull()
                                ?: error("Campaign round must be an integer: $line"),
                        baseSeed =
                            columns[1].toLongOrNull()
                                ?: error("Campaign seed must be a Long: $line"),
                        phase = Resolver26BroadStressCampaignPhase.fromId(columns[2]),
                    )
                }.toList()
        }
    }

    // Returns exactly one persisted campaign round.
    fun round(number: Int): Resolver26BroadStressCampaignRound =
        rounds.singleOrNull { round -> round.number == number }
            ?: error("Unknown Resolver26 broad stress campaign round $number")
}
