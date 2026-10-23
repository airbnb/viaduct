package viaduct.engine.runtime2.propertytest

import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratorConfigData
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.ResolverTestExecution
import viaduct.engine.runtime2.arbitrary.ResolverTestRun
import viaduct.engine.runtime2.arbitrary.SelectiveNodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.checkResolverTestBatch
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.arbitrary.configuredResolverTestExecution
import viaduct.engine.runtime2.arbitrary.resolverTestGenerationConfigured
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.utils.string.sha256Hash

const val RESOLVER_TEST_SUITE_FORMAT_VERSION = 1
const val RESOLVER_TEST_SUITES_RESOURCE_ROOT = "viaduct/engine/runtime2/property-tests/suites"

data class ResolverTestSuiteManifest(
    val formatVersion: Int,
    val id: String,
    val seed: Long,
    val counts: TestCaseCount,
    val fieldCheckerMode: GeneratedFieldCheckerMode,
    val typeCheckerMode: GeneratedTypeCheckerMode,
    val generatorConfigSha256: String,
    val sdlSha256: List<String>,
    val shardSha256: List<String>,
)

/**
 * Replays [definition]'s stored suite, or generates it live when a seed or size is configured for exploration.
 */
suspend fun checkResolverTestSuite(
    definition: ResolverTestSuiteDefinition,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun {
    val run =
        if (resolverTestGenerationConfigured()) {
            checkResolverTestCases(
                counts = definition.counts,
                config = definition.config,
                profile = definition.profile,
                fieldCheckerMode = definition.fieldCheckerMode,
                typeCheckerMode = definition.typeCheckerMode,
                property = property,
            )
        } else {
            ResolverTestSuite
                .load(definition)
                .replay(configuredResolverTestExecution(definition.counts, definition.profile), property)
        }
    run.assertAggregate(
        run.attemptedCases == run.expectedCases,
        "${definition.id} suite ran ${run.attemptedCases} cases; expected ${run.expectedCases}",
    )
    return run
}

/** A pre-generated suite whose schema shards load one at a time instead of being generated during the test run. */
class ResolverTestSuite private constructor(
    val definition: ResolverTestSuiteDefinition,
    private val manifest: ResolverTestSuiteManifest,
) {
    fun loadShard(schemaIndex: Int): ResolverTestBatch {
        val sdl = readVerified(sdlResource(definition.id, schemaIndex), manifest.sdlSha256[schemaIndex - 1])
        val json = readVerified(shardResource(definition.id, schemaIndex), manifest.shardSha256[schemaIndex - 1])
        return try {
            ResolverTestShardCodec.decode(schemaIndex, sdl, json)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("Could not decode resolver test suite ${definition.id} schema $schemaIndex from ${shardResource(definition.id, schemaIndex)}", failure)
        }
    }

    private fun readVerified(
        resource: String,
        sha256: String,
    ): ByteArray {
        val bytes = PropertyTestJson.readResourceBytes(resource)
        check(bytes.sha256Hash() == sha256) {
            "Resolver test suite ${definition.id} resource $resource does not match its manifest digest; $REGENERATE_HINT"
        }
        return bytes
    }

    suspend fun replay(
        execution: ResolverTestExecution,
        property: suspend (TestWorld, ResolverTestCase) -> Unit,
    ): ResolverTestRun {
        val counts = definition.counts
        require(!execution.sizeOverridden && execution.counts == counts) {
            "Resolver test suite ${definition.id} has fixed size $counts; use a seed to generate other sizes"
        }
        val schemaIndices = execution.selectedCase?.let { selected -> listOf(selected.schemaIndex) } ?: (1..counts.schemas)
        var attemptedCases = 0
        for (schemaIndex in schemaIndices) {
            attemptedCases +=
                checkResolverTestBatch(
                    batch = loadShard(schemaIndex),
                    schemaIndex = schemaIndex,
                    execution = execution,
                    profile = definition.profile,
                    seed = definition.seed,
                    selectiveNodeResolvers = definition.config[SelectiveNodeResolversEnabled],
                    fieldCheckerMode = definition.fieldCheckerMode,
                    typeCheckerMode = definition.typeCheckerMode,
                    property = property,
                )
        }
        return ResolverTestRun(
            profile = definition.profile,
            seed = definition.seed,
            attemptedCases = attemptedCases,
            counts = counts,
            selectedCase = execution.selectedCase,
            sizeOverridden = false,
            storedSuite = definition.id,
        )
    }

    companion object {
        fun load(definition: ResolverTestSuiteDefinition): ResolverTestSuite {
            val manifest = PropertyTestJson.readResource<ResolverTestSuiteManifest>(manifestResource(definition.id))
            require(manifest == definition.manifest(manifest.sdlSha256, manifest.shardSha256)) {
                "Resolver test suite ${definition.id} is stale relative to its definition; $REGENERATE_HINT"
            }
            require(manifest.sdlSha256.size == definition.counts.schemas && manifest.shardSha256.size == definition.counts.schemas) {
                "Resolver test suite ${definition.id} lists ${manifest.sdlSha256.size} schemas and ${manifest.shardSha256.size} shards for ${definition.counts.schemas} schemas"
            }
            return ResolverTestSuite(definition, manifest)
        }
    }
}

internal fun ResolverTestSuiteDefinition.manifest(
    sdlSha256: List<String>,
    shardSha256: List<String>,
): ResolverTestSuiteManifest =
    ResolverTestSuiteManifest(
        formatVersion = RESOLVER_TEST_SUITE_FORMAT_VERSION,
        id = id,
        seed = seed,
        counts = counts,
        fieldCheckerMode = fieldCheckerMode,
        typeCheckerMode = typeCheckerMode,
        generatorConfigSha256 = PropertyTestJson.write(GeneratorConfigData.from(id, config)).toByteArray().sha256Hash(),
        sdlSha256 = sdlSha256,
        shardSha256 = shardSha256,
    )

internal fun manifestResource(suiteId: String): String = "$RESOLVER_TEST_SUITES_RESOURCE_ROOT/$suiteId/manifest.json"

internal fun sdlResource(
    suiteId: String,
    schemaIndex: Int,
): String = "${schemaResourceBase(suiteId, schemaIndex)}.graphqls.gz"

internal fun shardResource(
    suiteId: String,
    schemaIndex: Int,
): String = "${schemaResourceBase(suiteId, schemaIndex)}.json.gz"

private fun schemaResourceBase(
    suiteId: String,
    schemaIndex: Int,
): String = "$RESOLVER_TEST_SUITES_RESOURCE_ROOT/$suiteId/schema-${schemaIndex.toString().padStart(3, '0')}"

private const val REGENERATE_HINT =
    "regenerate with ./gradlew :core:engine:runtime2:materializeResolverTestSuites " +
        "or bazel run //projects/viaduct/oss/core/engine/runtime2:materialize_resolver_test_suites"
