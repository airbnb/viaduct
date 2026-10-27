package viaduct.engine.runtime2.propertytest

import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratorConfigData
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_ID_PROPERTY
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.ResolverTestCaseCoordinate
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

const val RESOLVER_TEST_SUITE_FORMAT_VERSION = 2
const val RESOLVER_TEST_SUITES_RESOURCE_ROOT = "viaduct/engine/runtime2/property-tests/suites"

/** [counts] is the generated size; duplicate registries and queries are dropped before storing, so [batches] may hold fewer cases. */
data class ResolverTestSuiteManifest(
    val formatVersion: Int,
    val id: String,
    val seed: Long,
    val counts: TestCaseCount,
    val fieldCheckerMode: GeneratedFieldCheckerMode,
    val typeCheckerMode: GeneratedTypeCheckerMode,
    val generatorConfigSha256: String,
    val schemas: List<ResolverTestSuiteSchema>,
    val batches: List<ResolverTestSuiteBatch>,
)

/** A stored SDL, identified by a prefix of its SHA-256 and shared by every batch that names it. */
data class ResolverTestSuiteSchema(
    val id: String,
    val sdlSha256: String,
)

/** A stored batch: its stipulated [id], the ID of its schema, and the content IDs of its registries and queries, in order. */
data class ResolverTestSuiteBatch(
    val id: String,
    val schema: String,
    val shardSha256: String,
    val registries: List<String>,
    val queries: List<String>,
)

/**
 * Replays [definition]'s stored suite, or generates it live when a seed or size is configured for exploration.
 * A configured test-case ID replays only that case; suites that do not hold it run nothing.
 */
suspend fun checkResolverTestSuite(
    definition: ResolverTestSuiteDefinition,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun {
    val selected = configuredResolverTestCaseId()
    val run =
        if (resolverTestGenerationConfigured()) {
            require(selected == null) {
                "$RESOLVER_TEST_CASE_ID_PROPERTY replays a stored case; unset the configured seed and size"
            }
            checkResolverTestCases(
                counts = definition.counts,
                config = definition.config,
                profile = definition.profile,
                fieldCheckerMode = definition.fieldCheckerMode,
                typeCheckerMode = definition.typeCheckerMode,
                property = property,
            )
        } else {
            require(configuredResolverTestExecution(definition.counts, definition.profile).selectedCase == null) {
                "Stored suite ${definition.id} selects one case by test-case ID; set $RESOLVER_TEST_CASE_ID_PROPERTY=<suite/batch/registry/query>, " +
                    "or configure a seed to select S:R:Q from live generation"
            }
            when {
                selected == null -> ResolverTestSuite.load(definition).replay(property = property)
                selected.suite == definition.id -> ResolverTestSuite.load(definition).replay(selected, property)
                else -> definition.unselectedRun(selected)
            }
        }
    run.assertAggregate(
        run.attemptedCases == run.expectedCases,
        "${definition.id} suite ran ${run.attemptedCases} cases; expected ${run.expectedCases}",
    )
    return run
}

private fun ResolverTestSuiteDefinition.unselectedRun(selected: ResolverTestCaseId): ResolverTestRun {
    require(ResolverTestSuiteDefinition.entries.any { definition -> definition.id == selected.suite }) {
        "Test case $selected names unknown resolver test suite ${selected.suite}"
    }
    return ResolverTestRun(
        profile = profile,
        seed = seed,
        attemptedCases = 0,
        counts = counts,
        selectedCase = null,
        sizeOverridden = false,
        storedSuite = id,
        selectedTestCase = selected.toString(),
    )
}

/** A pre-generated suite whose batch shards load one at a time instead of being generated during the test run. */
class ResolverTestSuite private constructor(
    val definition: ResolverTestSuiteDefinition,
    private val manifest: ResolverTestSuiteManifest,
) {
    val batches: List<ResolverTestSuiteBatch>
        get() = manifest.batches

    private val sdlSha256BySchema: Map<String, String> = manifest.schemas.associate { schema -> schema.id to schema.sdlSha256 }

    val caseCount: Int
        get() = batches.sumOf { batch -> batch.registries.size * batch.queries.size }

    fun testCaseIds(): List<ResolverTestCaseId> =
        batches.flatMap { batch ->
            batch.registries.flatMap { registry ->
                batch.queries.map { query -> ResolverTestCaseId(definition.id, batch.id, registry, query) }
            }
        }

    fun loadShard(batchId: String): ResolverTestBatch {
        val stored =
            requireNotNull(batches.singleOrNull { batch -> batch.id == batchId }) {
                "Resolver test suite ${definition.id} holds no batch $batchId; it was removed or never existed"
            }
        val sdl = readVerified(sdlResource(definition.id, stored.schema), sdlSha256BySchema.getValue(stored.schema))
        val json = readVerified(shardResource(definition.id, batchId), stored.shardSha256)
        val batch =
            try {
                ResolverTestShardCodec.decode(batchId, sdl, json)
            } catch (failure: IllegalArgumentException) {
                throw IllegalStateException("Could not decode resolver test suite ${definition.id} batch $batchId from ${shardResource(definition.id, batchId)}", failure)
            }
        check(ResolverTestShardCodec.ids(batch) == ResolverTestShardIds(stored.schema, stored.registries, stored.queries)) {
            "Resolver test suite ${definition.id} batch $batchId content does not match the IDs in its manifest; $REGENERATE_HINT"
        }
        return batch
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

    /** Replays every stored case, or only [selected]. */
    suspend fun replay(
        selected: ResolverTestCaseId? = null,
        property: suspend (TestWorld, ResolverTestCase) -> Unit,
    ): ResolverTestRun {
        require(selected == null || selected.suite == definition.id) {
            "Test case $selected is not in resolver test suite ${definition.id}"
        }
        val selectedBatches =
            if (selected == null) {
                batches.withIndex().toList()
            } else {
                listOf(
                    requireNotNull(batches.withIndex().singleOrNull { (_, batch) -> batch.id == selected.batch }) {
                        "Resolver test suite ${definition.id} holds no batch ${selected.batch} for test case $selected; it was removed or never existed"
                    },
                )
            }
        var attemptedCases = 0
        for ((offset, stored) in selectedBatches) {
            val schemaIndex = offset + 1
            attemptedCases +=
                checkResolverTestBatch(
                    batch = loadShard(stored.id),
                    schemaIndex = schemaIndex,
                    execution = ResolverTestExecution(definition.counts, selected?.let { stored.caseCoordinate(schemaIndex, it) }),
                    profile = definition.profile,
                    seed = definition.seed,
                    selectiveNodeResolvers = definition.config[SelectiveNodeResolversEnabled],
                    fieldCheckerMode = definition.fieldCheckerMode,
                    typeCheckerMode = definition.typeCheckerMode,
                    testCaseId = { registryIndex, queryIndex ->
                        ResolverTestCaseId(definition.id, stored.id, stored.registries[registryIndex - 1], stored.queries[queryIndex - 1]).toString()
                    },
                    property = property,
                )
        }
        selected?.let(::markResolverTestCaseRan)
        return ResolverTestRun(
            profile = definition.profile,
            seed = definition.seed,
            attemptedCases = attemptedCases,
            counts = definition.counts,
            selectedCase = null,
            sizeOverridden = false,
            storedSuite = definition.id,
            storedCases = caseCount,
            selectedTestCase = selected?.toString(),
        )
    }

    private fun ResolverTestSuiteBatch.caseCoordinate(
        schemaIndex: Int,
        selected: ResolverTestCaseId,
    ): ResolverTestCaseCoordinate {
        val registryIndex = registries.indexOf(selected.registry) + 1
        val queryIndex = queries.indexOf(selected.query) + 1
        require(registryIndex > 0) {
            "Resolver test suite ${definition.id} batch $id holds no registry ${selected.registry} for test case $selected; it was removed or never existed"
        }
        require(queryIndex > 0) {
            "Resolver test suite ${definition.id} batch $id holds no query ${selected.query} for test case $selected; it was removed or never existed"
        }
        return ResolverTestCaseCoordinate(schemaIndex, registryIndex, queryIndex)
    }

    companion object {
        fun load(definition: ResolverTestSuiteDefinition): ResolverTestSuite {
            val manifest = PropertyTestJson.readResource<ResolverTestSuiteManifest>(manifestResource(definition.id))
            require(manifest.copy(schemas = emptyList(), batches = emptyList()) == definition.manifest(emptyList(), emptyList())) {
                "Resolver test suite ${definition.id} is stale relative to its definition; $REGENERATE_HINT"
            }
            require(manifest.batches.isNotEmpty() && manifest.batches.map(ResolverTestSuiteBatch::id).allDistinct()) {
                "Resolver test suite ${definition.id} must list at least one batch, each with a distinct ID"
            }
            require(
                manifest.schemas.map(
                    ResolverTestSuiteSchema::id
                ).allDistinct() && manifest.schemas.map(ResolverTestSuiteSchema::id).toSet() == manifest.batches.map(ResolverTestSuiteBatch::schema).toSet()
            ) {
                "Resolver test suite ${definition.id} must list each schema once, and exactly the schemas its batches use"
            }
            manifest.batches.forEach { batch ->
                require(batch.registries.isNotEmpty() && batch.queries.isNotEmpty() && batch.registries.allDistinct() && batch.queries.allDistinct()) {
                    "Resolver test suite ${definition.id} batch ${batch.id} must list registries and queries, each with a distinct ID"
                }
            }
            return ResolverTestSuite(definition, manifest)
        }
    }
}

internal fun ResolverTestSuiteDefinition.manifest(
    schemas: List<ResolverTestSuiteSchema>,
    batches: List<ResolverTestSuiteBatch>,
): ResolverTestSuiteManifest =
    ResolverTestSuiteManifest(
        formatVersion = RESOLVER_TEST_SUITE_FORMAT_VERSION,
        id = id,
        seed = seed,
        counts = counts,
        fieldCheckerMode = fieldCheckerMode,
        typeCheckerMode = typeCheckerMode,
        generatorConfigSha256 = PropertyTestJson.write(GeneratorConfigData.from(id, config)).toByteArray().sha256Hash(),
        schemas = schemas,
        batches = batches,
    )

internal fun List<String>.allDistinct(): Boolean = toSet().size == size

internal fun manifestResource(suiteId: String): String = "$RESOLVER_TEST_SUITES_RESOURCE_ROOT/$suiteId/manifest.json"

internal fun sdlResource(
    suiteId: String,
    schemaId: String,
): String = "$RESOLVER_TEST_SUITES_RESOURCE_ROOT/$suiteId/schema-$schemaId.graphqls.gz"

internal fun shardResource(
    suiteId: String,
    batchId: String,
): String = "$RESOLVER_TEST_SUITES_RESOURCE_ROOT/$suiteId/batch-$batchId.json.gz"

// Generation order is fixed by the suite's seed, so a batch keeps its ordinal when other batches are pruned from the manifest.
internal fun resolverTestBatchId(ordinal: Int): String = "b" + ordinal.toString().padStart(3, '0')

private const val REGENERATE_HINT =
    "regenerate with ./gradlew :core:engine:runtime2:materializeResolverTestSuites " +
        "or bazel run //projects/viaduct/oss/core/engine/runtime2:materialize_resolver_test_suites"
