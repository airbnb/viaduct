package viaduct.engine.runtime2.propertytest

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.InputStream
import viaduct.engine.runtime2.arbitrary.GeneratorConfigData
import viaduct.engine.runtime2.arbitrary.TestCaseCount

const val PROPERTY_TEST_ROUND_FORMAT_VERSION = 1
const val PROPERTY_TEST_CAMPAIGN_FORMAT_VERSION = 1
const val GENERATOR_CONFIG_INDEX_FORMAT_VERSION = 2

data class GeneratorConfigResourceIndex(
    val formatVersion: Int,
    val resources: List<String>,
)

data class PropertyTestRoundConfigFile(
    val formatVersion: Int,
    val id: String,
    val runs: List<PropertyTestRunConfig>,
)

data class PropertyTestRunConfig(
    val subjectProfileId: String,
    val testInputProfileId: String,
    val seed: Long,
    val counts: TestCaseCount,
    val requiredCoverage: Set<String> = emptySet(),
)

data class PropertyTestCampaignConfigFile(
    val formatVersion: Int,
    val id: String,
    val subjectProfileId: String,
    val seedMultiplier: Long,
    val profiles: List<PropertyTestCampaignProfileConfig>,
    val phases: Map<String, PropertyTestCampaignPhaseConfig>,
    val rounds: List<PropertyTestCampaignRoundRangeConfig>,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PropertyTestCampaignProfileConfig(
    val id: String,
    val seedOffset: Long,
    val testInputProfiles: Map<String, String>,
    val requiredCoverage: Set<String> = emptySet(),
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PropertyTestCampaignPhaseConfig(
    val testInputVariant: String,
    val counts: TestCaseCount,
    val profileOverrides: Map<String, PropertyTestCampaignPhaseProfileOverride> = emptyMap(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PropertyTestCampaignPhaseProfileOverride(
    val testInputVariant: String? = null,
    val counts: TestCaseCount? = null,
)

data class PropertyTestCampaignRoundRangeConfig(
    val first: Int,
    val last: Int,
    val baseSeed: Long,
    val phaseId: String,
)

fun PropertyTestCampaignConfigFile.roundNumbers(): List<Int> {
    validate()
    return rounds.flatMap { range -> (range.first..range.last).toList() }
}

fun PropertyTestCampaignConfigFile.roundConfig(
    number: Int,
    selectedProfileId: String? = null,
): PropertyTestRoundConfigFile {
    validate()
    val campaignRoundRange =
        rounds.singleOrNull { range -> number in range.first..range.last }
            ?: error("Campaign $id has no round $number")
    val phase = phases.getValue(campaignRoundRange.phaseId)
    val selectedProfiles =
        profiles.filter { profile ->
            selectedProfileId == null || profile.id == selectedProfileId
        }
    require(selectedProfiles.isNotEmpty()) {
        "Campaign $id has no profile $selectedProfileId"
    }
    return PropertyTestRoundConfigFile(
        formatVersion = PROPERTY_TEST_ROUND_FORMAT_VERSION,
        id = "$id-round-${number.toString().padStart(3, '0')}",
        runs =
            selectedProfiles.map { profile ->
                val overrides = phase.profileOverrides[profile.id]
                val variant = overrides?.testInputVariant ?: phase.testInputVariant
                val testInputProfileId =
                    profile.testInputProfiles[variant]
                        ?: error(
                            "Campaign $id profile ${profile.id} has no test-input " +
                                "variant $variant",
                        )
                PropertyTestRunConfig(
                    subjectProfileId = subjectProfileId,
                    testInputProfileId = testInputProfileId,
                    seed =
                        Math.addExact(
                            Math.multiplyExact(
                                Math.addExact(
                                    campaignRoundRange.baseSeed,
                                    (number - campaignRoundRange.first).toLong(),
                                ),
                                seedMultiplier,
                            ),
                            profile.seedOffset,
                        ),
                    counts = overrides?.counts ?: phase.counts,
                    requiredCoverage = profile.requiredCoverage,
                )
            },
    )
}

private fun PropertyTestCampaignConfigFile.validate() {
    require(formatVersion == PROPERTY_TEST_CAMPAIGN_FORMAT_VERSION) {
        "Unsupported property-test campaign formatVersion $formatVersion"
    }
    require(id.isNotBlank()) { "Property-test campaign id must not be blank" }
    require(subjectProfileId.isNotBlank()) {
        "Property-test campaign $id subjectProfileId must not be blank"
    }
    require(seedMultiplier > 0) {
        "Property-test campaign $id seedMultiplier must be positive"
    }
    require(profiles.isNotEmpty()) { "Property-test campaign $id has no profiles" }
    require(profiles.map(PropertyTestCampaignProfileConfig::id).distinct().size == profiles.size) {
        "Property-test campaign $id has duplicate profile ids"
    }
    require(phases.isNotEmpty()) { "Property-test campaign $id has no phases" }
    require(rounds.isNotEmpty()) { "Property-test campaign $id has no rounds" }
    profiles.forEach { profile ->
        require(profile.id.isNotBlank())
        require(profile.testInputProfiles.isNotEmpty()) {
            "Property-test campaign $id profile ${profile.id} has no test-input profiles"
        }
    }
    phases.forEach { (phaseId, phase) ->
        require(phaseId.isNotBlank())
        require(phase.testInputVariant.isNotBlank())
        val unknownProfiles = phase.profileOverrides.keys - profiles.map { profile -> profile.id }.toSet()
        require(unknownProfiles.isEmpty()) {
            "Property-test campaign $id phase $phaseId overrides unknown profiles: " +
                unknownProfiles
        }
    }
    rounds.forEach { round ->
        require(round.first > 0) {
            "Property-test campaign $id round range must start above zero: $round"
        }
        require(round.last >= round.first) {
            "Property-test campaign $id round range ends before it starts: $round"
        }
        require(round.phaseId in phases) {
            "Property-test campaign $id round range $round has unknown phase ${round.phaseId}"
        }
    }
    val roundNumbers = rounds.flatMap { range -> (range.first..range.last).toList() }
    require(roundNumbers.distinct().size == roundNumbers.size) {
        "Property-test campaign $id has overlapping round ranges"
    }
}

class GeneratorConfigRegistry private constructor(
    private val dataById: Map<String, GeneratorConfigData>,
) {
    operator fun get(id: String): GeneratorConfigData =
        dataById[id]
            ?: error(
                "Unknown test-input profile $id; profiles=${dataById.keys.sorted()}",
            )

    val ids: Set<String>
        get() = dataById.keys

    companion object {
        fun load(
            indexResource: String,
            classLoader: ClassLoader = defaultClassLoader(),
        ): GeneratorConfigRegistry {
            val index =
                PropertyTestJson.readResource<GeneratorConfigResourceIndex>(
                    indexResource,
                    classLoader,
                )
            require(index.formatVersion == GENERATOR_CONFIG_INDEX_FORMAT_VERSION) {
                "Unsupported generator config index formatVersion ${index.formatVersion}"
            }
            val data =
                index.resources.map { resource ->
                    PropertyTestJson.readResource<GeneratorConfigData>(
                        resource,
                        classLoader,
                    )
                }
            require(data.map(GeneratorConfigData::id).distinct().size == data.size) {
                "Generator config index $indexResource contains duplicate ids"
            }
            data.forEach(GeneratorConfigData::toConfig)
            return GeneratorConfigRegistry(data.associateBy(GeneratorConfigData::id))
        }
    }
}

object PropertyTestJson {
    inline fun <reified T> read(json: String): T = mapper.readValue(json)

    inline fun <reified T> read(stream: InputStream): T = mapper.readValue(stream)

    inline fun <reified T> readResource(
        resource: String,
        classLoader: ClassLoader = defaultClassLoader(),
    ): T =
        requireNotNull(classLoader.getResourceAsStream(resource.removePrefix("/"))) {
            "Missing property-test resource $resource"
        }.use(::read)

    fun write(value: Any): String = mapper.writeValueAsString(value) + "\n"

    @PublishedApi
    internal val mapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
}

@PublishedApi
internal fun defaultClassLoader(): ClassLoader = Thread.currentThread().contextClassLoader ?: GeneratorConfigRegistry::class.java.classLoader
