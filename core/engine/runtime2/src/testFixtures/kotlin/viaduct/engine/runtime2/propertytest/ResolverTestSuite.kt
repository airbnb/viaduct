package viaduct.engine.runtime2.propertytest

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import viaduct.engine.runtime2.arbitrary.ConfigKey
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
import viaduct.engine.runtime2.resolution.ResolutionBroadStressCases
import viaduct.utils.string.sha256Hash

const val RESOLVER_TEST_SUITE_FORMAT_VERSION = 4
const val RESOLVER_TEST_SUITES_RESOURCE_ROOT = "viaduct/engine/runtime2/property-tests/suites"

/** The stored batches are authoritative and the [generations] are their provenance: each batch names the generation it came from, and later generations only add batches. */
data class ResolverTestSuiteManifest(
    val formatVersion: Int,
    val id: String,
    val fieldCheckerMode: GeneratedFieldCheckerMode,
    val typeCheckerMode: GeneratedTypeCheckerMode,
    val generations: List<ResolverTestSuiteGeneration>,
    val schemas: List<ResolverTestSuiteSchema>,
    val batches: List<ResolverTestSuiteBatch>,
)

/**
 * The inputs one generation's pool was generated from; [counts] is the pool size, of which the suite may keep only some batches.
 * [generatorConfig] is a record, read with [GeneratorConfigData.recordedValue], so keys added later read as their defaults.
 */
data class ResolverTestSuiteGeneration(
    val id: String,
    val seed: Long,
    val counts: TestCaseCount,
    val generatorConfig: GeneratorConfigData,
) {
    companion object {
        /** [definition]'s current inputs, as a new generation [id]. */
        fun of(
            definition: ResolverTestSuiteDefinition,
            id: String,
        ): ResolverTestSuiteGeneration = ResolverTestSuiteGeneration(id, definition.seed, definition.counts, GeneratorConfigData.from(definition.id, definition.config))
    }
}

/** A stored SDL, identified by a prefix of its SHA-256 and shared by every batch that names it. */
data class ResolverTestSuiteSchema(
    val id: String,
    val sdlSha256: String,
)

/**
 * A stored batch: its [id] `<generation>-<poolBatch>`, its position [poolBatch] in that generation's pool, the ID of its schema,
 * and the content IDs of its registry and of its queries, in order. The shard records [poolBatch], the ID it was generated under.
 */
data class ResolverTestSuiteBatch(
    val id: String,
    val generation: String,
    val poolBatch: String,
    val schema: String,
    val shardSha256: String,
    val registry: String,
    val queries: List<String>,
) {
    fun ids(): ResolverTestShardIds = ResolverTestShardIds(schema, registry, queries)
}

/**
 * Replays [definition]'s stored suite, or generates it live when a seed or size is configured for exploration.
 * A configured test-case ID replays only that case; suites that do not hold it run nothing.
 */
suspend fun checkResolverTestSuite(
    definition: ResolverTestSuiteDefinition,
    selectiveResolvers: Boolean = true,
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
                selectiveResolvers = selectiveResolvers,
                property = property,
            )
        } else {
            require(configuredResolverTestExecution(definition.counts, definition.profile).selectedCase == null) {
                "Stored suite ${definition.id} selects one case by test-case ID; set $RESOLVER_TEST_CASE_ID_PROPERTY=<suite/batch/query>, " +
                    "or configure a seed to select S:Q from live generation"
            }
            replayResolverTestSuite(definition, selected, selectiveResolvers, property)
        }
    run.assertAggregate(
        run.attemptedCases == run.expectedCases,
        "${definition.id} suite ran ${run.attemptedCases} cases; expected ${run.expectedCases}",
    )
    return run
}

/** This suite as the stored cases of a Resolution stress run, checked as [checkResolverTestSuite] checks them. */
fun ResolverTestSuiteDefinition.broadStressCases(): ResolutionBroadStressCases =
    ResolutionBroadStressCases.stored(profile, config, counts, seed) { property -> checkResolverTestSuite(this, property = property) }

/** Replays [definition]'s stored suite, or only [selected] when it holds it; reads no configured seed, size, or test case. */
suspend fun replayResolverTestSuite(
    definition: ResolverTestSuiteDefinition,
    selected: ResolverTestCaseId?,
    selectiveResolvers: Boolean = true,
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): ResolverTestRun =
    when {
        selected == null -> ResolverTestSuite.load(definition).replay(selectiveResolvers = selectiveResolvers, property = property)
        selected.suite == definition.id -> ResolverTestSuite.load(definition).replay(selected = selected, selectiveResolvers = selectiveResolvers, property = property)
        else -> definition.unselectedRun(selected)
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
        storedStressSuite = variant == ResolverTestSuiteVariant.STRESS,
    )
}

/** A stored suite whose batch shards load one at a time instead of being generated during the test run. */
class ResolverTestSuite private constructor(
    val definition: ResolverTestSuiteDefinition,
    private val manifest: ResolverTestSuiteManifest,
    private val entries: Map<String, ByteArray>,
) {
    val batches: List<ResolverTestSuiteBatch>
        get() = manifest.batches

    val generations: List<ResolverTestSuiteGeneration>
        get() = manifest.generations

    private val sdlSha256BySchema: Map<String, String> = manifest.schemas.associate { schema -> schema.id to schema.sdlSha256 }

    private val generationsById: Map<String, ResolverTestSuiteGeneration> = manifest.generations.associateBy(ResolverTestSuiteGeneration::id)

    // Legacy test-case IDs, printed before generations existed, name a first-generation batch by its pool position.
    private val indexedBatches: Map<String, IndexedValue<ResolverTestSuiteBatch>> =
        manifest.batches.withIndex().let { indexed ->
            indexed.filter { (_, batch) -> batch.generation == FIRST_GENERATION_ID }.associateBy { it.value.poolBatch } + indexed.associateBy { it.value.id }
        }

    val caseCount: Int
        get() = batches.sumOf { batch -> batch.queries.size }

    fun testCaseIds(): List<ResolverTestCaseId> = batches.flatMap { batch -> batch.queries.map { query -> ResolverTestCaseId(definition.id, batch.id, query) } }

    private fun indexedBatch(batchId: String): IndexedValue<ResolverTestSuiteBatch>? = indexedBatches[batchId]

    /** The batch [batchId] names, accepting a first-generation pool ID such as `b001` for `g1-b001`. */
    fun batch(batchId: String): ResolverTestSuiteBatch? = indexedBatch(batchId)?.value

    fun loadShard(batchId: String): ResolverTestBatch {
        val stored =
            requireNotNull(indexedBatch(batchId)) {
                "Resolver test suite ${definition.id} holds no batch $batchId; it was removed or never existed"
            }.value
        val sdl = readVerified(sdlEntry(stored.schema), sdlSha256BySchema.getValue(stored.schema))
        val json = readVerified(shardEntry(stored.id), stored.shardSha256)
        val batch =
            try {
                ResolverTestShardCodec.decode(stored.poolBatch, sdl, json)
            } catch (failure: IllegalArgumentException) {
                throw IllegalStateException("Could not decode resolver test suite ${definition.id} batch ${stored.id} from ${suiteArchiveResource(definition.id)}!${shardEntry(stored.id)}", failure)
            }
        check(ResolverTestShardCodec.ids(batch) == stored.ids()) {
            "Resolver test suite ${definition.id} batch ${stored.id} content does not match the IDs in its manifest; $ARCHIVE_HINT"
        }
        return batch
    }

    private fun readVerified(
        entry: String,
        sha256: String,
    ): ByteArray {
        val bytes = definition.readEntry(entries, entry)
        check(bytes.sha256Hash() == sha256) {
            "Resolver test suite ${definition.id} entry $entry does not match its manifest digest; $ARCHIVE_HINT"
        }
        return bytes
    }

    /** Replays every stored case, or only [selected]. */
    suspend fun replay(
        selected: ResolverTestCaseId? = null,
        selectiveResolvers: Boolean = true,
        selectedBatchId: String? = null,
        property: suspend (TestWorld, ResolverTestCase) -> Unit,
    ): ResolverTestRun {
        require(selected == null || selectedBatchId == null) { "Select either one case or one batch, not both" }
        require(selected == null || selected.suite == definition.id) {
            "Test case $selected is not in resolver test suite ${definition.id}"
        }
        val selectedBatches =
            when {
                selected != null ->
                    listOf(
                        requireNotNull(indexedBatch(selected.batch)) {
                            "Resolver test suite ${definition.id} holds no batch ${selected.batch} for test case $selected; it was removed or never existed"
                        },
                    )
                selectedBatchId != null ->
                    listOf(
                        requireNotNull(indexedBatch(selectedBatchId)) {
                            "Resolver test suite ${definition.id} holds no batch $selectedBatchId; it was removed or never existed"
                        },
                    )
                else -> batches.withIndex().toList()
            }
        var attemptedCases = 0
        for ((_, stored) in selectedBatches) {
            // A stored case keeps its pool coordinates, so dropping or appending batches never moves it outside its bounds.
            val schemaIndex = resolverTestBatchOrdinal(stored.poolBatch)
            val generation = generationsById.getValue(stored.generation)
            attemptedCases +=
                definition.checkNumberedBatch(
                    batchId = stored.id,
                    batch = loadShard(stored.id),
                    queries = stored.queries,
                    schemaIndex = schemaIndex,
                    seed = generation.seed,
                    counts = TestCaseCount(schemas = schemaIndex, queriesPerSchema = stored.queries.size),
                    selected = selected?.let { stored.caseCoordinate(schemaIndex, it) },
                    selectiveResolvers = selectiveResolvers,
                    selectiveNodeResolvers = generation.generatorConfig.recordedValue(SelectiveNodeResolversEnabled),
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
            storedStressSuite = definition.variant == ResolverTestSuiteVariant.STRESS,
        )
    }

    /** Keeps only [batchIds] and the schemas they use; every generation stays recorded, so a dropped generation's ID is never reused. */
    fun retaining(batchIds: Collection<String>): ResolverTestSuite {
        val retained = batchIds.toSet()
        require(retained.isNotEmpty()) { "Resolver test suite ${definition.id} must retain at least one batch" }
        val unknown = retained - batches.map(ResolverTestSuiteBatch::id).toSet()
        require(unknown.isEmpty()) { "Resolver test suite ${definition.id} holds no batches $unknown" }
        val keptBatches = batches.filter { batch -> batch.id in retained }
        val keptSchemas = keptBatches.mapTo(mutableSetOf(), ResolverTestSuiteBatch::schema)
        return of(
            definition,
            manifest.copy(
                schemas = manifest.schemas.filter { schema -> schema.id in keptSchemas },
                batches = keptBatches,
            ),
            entries,
        )
    }

    /** Applies [ResolverTestSuiteDefinition.retainedBatches], which name pool positions; a definition without them keeps every batch. */
    fun retainingDefinitionBatches(): ResolverTestSuite {
        if (definition.retainedBatches.isEmpty()) return this
        val missing = definition.retainedBatches - batches.map(ResolverTestSuiteBatch::poolBatch).toSet()
        require(missing.isEmpty()) { "Resolver test suite ${definition.id} pool holds no batches $missing" }
        return retaining(batches.filter { batch -> batch.poolBatch in definition.retainedBatches }.map(ResolverTestSuiteBatch::id))
    }

    fun parts(partBytes: Int = RESOLVER_TEST_SUITE_PART_BYTES): List<ByteArray> = writeResolverTestSuiteParts(entries, partBytes)

    fun writeTo(
        resourcesRoot: Path,
        partBytes: Int = RESOLVER_TEST_SUITE_PART_BYTES,
    ) = writeResolverTestSuiteArchives(resourcesRoot, definition.id, entries, partBytes)

    private fun ResolverTestSuiteBatch.caseCoordinate(
        schemaIndex: Int,
        selected: ResolverTestCaseId,
    ): ResolverTestCaseCoordinate {
        val queryIndex = queries.indexOf(selected.query) + 1
        require(queryIndex > 0) {
            "Resolver test suite ${definition.id} batch $id holds no query ${selected.query} for test case $selected; it was removed or never existed"
        }
        return ResolverTestCaseCoordinate(schemaIndex, queryIndex)
    }

    companion object {
        fun load(definition: ResolverTestSuiteDefinition): ResolverTestSuite = fromEntries(definition, readResolverTestSuiteParts(definition.id, PropertyTestJson::readResourceBytesOrNull))

        /** Validates archive [entries] holding [definition]'s suite in the current format. */
        fun fromEntries(
            definition: ResolverTestSuiteDefinition,
            entries: Map<String, ByteArray>,
        ): ResolverTestSuite {
            val manifest = PropertyTestJson.mapper.readTree(definition.readEntry(entries, MANIFEST_ENTRY))
            val formatVersion = manifest.path("formatVersion").asInt()
            require(formatVersion == RESOLVER_TEST_SUITE_FORMAT_VERSION) {
                "Resolver test suite ${definition.id} has manifest formatVersion $formatVersion; expected $RESOLVER_TEST_SUITE_FORMAT_VERSION; $FORMAT_HINT"
            }
            return validated(definition, PropertyTestJson.mapper.treeToValue(manifest, ResolverTestSuiteManifest::class.java), entries)
        }

        /** Stores every batch as the first generation, including batches that share a schema; their SDL is stored once. */
        fun fromGenerated(
            definition: ResolverTestSuiteDefinition,
            generated: List<ResolverTestBatch>,
        ): ResolverTestSuite {
            val generation = ResolverTestSuiteGeneration.of(definition, FIRST_GENERATION_ID)
            val shards = linkedMapOf<String, ByteArray>()
            val sdlBySchema = linkedMapOf<String, ByteArray>()
            val batches =
                definition.numberBatches(generated).map { (poolBatch, batch, ids) ->
                    val batchId = generationBatchId(generation.id, poolBatch)
                    val shard = ResolverTestShardCodec.encode(poolBatch, batch)
                    val reencoded = ResolverTestShardCodec.encode(poolBatch, ResolverTestShardCodec.decode(poolBatch, shard.sdl, shard.json))
                    check(reencoded.sdl.contentEquals(shard.sdl) && reencoded.json.contentEquals(shard.json)) {
                        "Suite ${definition.id} batch $batchId does not round-trip through the shard codec"
                    }
                    check(sdlBySchema.getOrPut(ids.schema) { shard.sdl }.contentEquals(shard.sdl)) {
                        "Suite ${definition.id} has distinct schemas whose IDs collide at ${ids.schema}"
                    }
                    shards[shardEntry(batchId)] = shard.json
                    ResolverTestSuiteBatch(batchId, generation.id, poolBatch, ids.schema, shard.json.sha256Hash(), ids.registry, ids.queries)
                }
            val schemas = sdlBySchema.map { (schemaId, sdl) -> ResolverTestSuiteSchema(schemaId, sdl.sha256Hash()) }
            val manifest = definition.manifest(listOf(generation), schemas, batches)
            return of(definition, manifest, sdlBySchema.mapKeys { (schemaId, _) -> sdlEntry(schemaId) } + shards)
        }

        private fun of(
            definition: ResolverTestSuiteDefinition,
            manifest: ResolverTestSuiteManifest,
            source: Map<String, ByteArray>,
        ): ResolverTestSuite {
            val entries =
                linkedMapOf(MANIFEST_ENTRY to PropertyTestJson.write(manifest).toByteArray()) +
                    manifest.schemas.associate { schema -> sdlEntry(schema.id) to definition.readEntry(source, sdlEntry(schema.id)) } +
                    manifest.batches.associate { batch -> shardEntry(batch.id) to definition.readEntry(source, shardEntry(batch.id)) }
            return validated(definition, manifest, entries)
        }

        private fun validated(
            definition: ResolverTestSuiteDefinition,
            manifest: ResolverTestSuiteManifest,
            entries: Map<String, ByteArray>,
        ): ResolverTestSuite {
            require(manifest.id == definition.id) {
                "Resolver test suite ${definition.id} archive holds suite ${manifest.id}; $ARCHIVE_HINT"
            }
            require(manifest.fieldCheckerMode == definition.fieldCheckerMode && manifest.typeCheckerMode == definition.typeCheckerMode) {
                "Resolver test suite ${definition.id} stores ${manifest.fieldCheckerMode}/${manifest.typeCheckerMode} checker cases, " +
                    "but its definition runs ${definition.fieldCheckerMode}/${definition.typeCheckerMode}; checker modes define a suite, so add a new suite for them"
            }
            val generationIds = manifest.generations.map(ResolverTestSuiteGeneration::id)
            // Generations are only appended, so the last one is the newest and is the baseline for config drift.
            require(generationIds.isNotEmpty() && generationIds == generationIds.indices.map { index -> "g${index + 1}" }) {
                "Resolver test suite ${definition.id} must list its generations in order as g1, g2, ...; it lists $generationIds"
            }
            require(manifest.batches.isNotEmpty() && manifest.batches.map(ResolverTestSuiteBatch::id).allDistinct()) {
                "Resolver test suite ${definition.id} must list at least one batch, each with a distinct ID"
            }
            require(generationIds.containsAll(manifest.batches.map(ResolverTestSuiteBatch::generation))) {
                "Resolver test suite ${definition.id} must list every generation its batches use"
            }
            require(
                manifest.schemas.map(
                    ResolverTestSuiteSchema::id
                ).allDistinct() && manifest.schemas.map(ResolverTestSuiteSchema::id).toSet() == manifest.batches.map(ResolverTestSuiteBatch::schema).toSet()
            ) {
                "Resolver test suite ${definition.id} must list each schema once, and exactly the schemas its batches use"
            }
            manifest.batches.forEach { batch ->
                require(batch.id == generationBatchId(batch.generation, batch.poolBatch)) {
                    "Resolver test suite ${definition.id} batch ${batch.id} must be named ${generationBatchId(batch.generation, batch.poolBatch)}"
                }
                require(batch.queries.isNotEmpty() && batch.queries.allDistinct()) {
                    "Resolver test suite ${definition.id} batch ${batch.id} must list queries, each with a distinct ID"
                }
            }
            val latest = manifest.generations.last()
            val drift = definition.configDrift(latest)
            require(drift.isEmpty()) {
                "Resolver test suite ${definition.id} definition sets ${drift.joinToString()} differently from its latest generation ${latest.id}; " +
                    "restore the definition, or materialize the suite again with --regenerate"
            }
            return ResolverTestSuite(definition, manifest, entries)
        }
    }
}

/**
 * The keys [ResolverTestSuiteDefinition.config] sets whose values differ from [generation]'s record.
 * Keys the definition leaves at their defaults are not compared, so adding a key or changing generator internals never invalidates a stored suite.
 */
internal fun ResolverTestSuiteDefinition.configDrift(generation: ResolverTestSuiteGeneration): List<String> =
    config.explicitKeys().mapNotNull { key ->
        @Suppress("UNCHECKED_CAST")
        val typed = key as ConfigKey<Any?>
        val defined = config[typed]
        val recorded = generation.generatorConfig.recordedValue(typed)
        if (defined == recorded) null else "${key.wireName}=$defined (recorded $recorded)"
    }

internal fun ResolverTestSuiteDefinition.manifest(
    generations: List<ResolverTestSuiteGeneration>,
    schemas: List<ResolverTestSuiteSchema>,
    batches: List<ResolverTestSuiteBatch>,
): ResolverTestSuiteManifest =
    ResolverTestSuiteManifest(
        formatVersion = RESOLVER_TEST_SUITE_FORMAT_VERSION,
        id = id,
        fieldCheckerMode = fieldCheckerMode,
        typeCheckerMode = typeCheckerMode,
        generations = generations,
        schemas = schemas,
        batches = batches,
    )

const val FIRST_GENERATION_ID = "g1"

internal fun generationBatchId(
    generation: String,
    poolBatch: String,
): String = "$generation-$poolBatch"

internal fun List<String>.allDistinct(): Boolean = toSet().size == size

/** Part 1 is `<id>.zip`; a suite too large for one part continues in `<id>.part2.zip`, `<id>.part3.zip`, and so on. */
fun suiteArchiveResource(
    suiteId: String,
    part: Int = 1,
): String = "$RESOLVER_TEST_SUITES_RESOURCE_ROOT/$suiteId" + (if (part == 1) "" else ".part$part") + ".zip"

// Keeps each checked-in part under git.musta.ch's recommended 5 MB file size.
const val RESOLVER_TEST_SUITE_PART_BYTES = 4_500_000

const val MANIFEST_ENTRY = "manifest.json"

fun sdlEntry(schemaId: String): String = "schema-$schemaId.graphqls.gz"

fun shardEntry(batchId: String): String = "batch-$batchId.json.gz"

fun readResolverTestSuiteArchive(bytes: ByteArray): Map<String, ByteArray> =
    ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.associate { entry -> entry.name to zip.readBytes() }
    }

/** Merges [suiteId]'s parts, read through [readPart] until a part is missing; an entry stored in two parts is rejected. */
fun readResolverTestSuiteParts(
    suiteId: String,
    readPart: (String) -> ByteArray?,
): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    for (part in generateSequence(1) { part -> part + 1 }) {
        val bytes = readPart(suiteArchiveResource(suiteId, part)) ?: break
        readResolverTestSuiteArchive(bytes).forEach { (name, entry) ->
            check(entries.put(name, entry) == null) { "Resolver test suite $suiteId stores entry $name in more than one part" }
        }
    }
    return requireNotNull(entries.takeIf(Map<String, ByteArray>::isNotEmpty)) {
        "Missing resolver test suite resource ${suiteArchiveResource(suiteId)}; $MATERIALIZE_HINT"
    }
}

/** Writes [entries] as [suiteId]'s archive parts under [resourcesRoot], deleting trailing parts left by a larger earlier archive. */
internal fun writeResolverTestSuiteArchives(
    resourcesRoot: Path,
    suiteId: String,
    entries: Map<String, ByteArray>,
    partBytes: Int = RESOLVER_TEST_SUITE_PART_BYTES,
) {
    Files.createDirectories(resourcesRoot.resolve(suiteArchiveResource(suiteId)).parent)
    val parts = writeResolverTestSuiteParts(entries, partBytes)
    parts.forEachIndexed { index, part -> Files.write(resourcesRoot.resolve(suiteArchiveResource(suiteId, index + 1)), part) }
    var stalePart = parts.size + 1
    while (Files.deleteIfExists(resourcesRoot.resolve(suiteArchiveResource(suiteId, stalePart)))) stalePart += 1
}

/** Packs [entries] in order into archives of at most [partBytes] each, starting a new part before one would overflow. */
internal fun writeResolverTestSuiteParts(
    entries: Map<String, ByteArray>,
    partBytes: Int,
): List<ByteArray> {
    val parts = mutableListOf(linkedMapOf<String, ByteArray>())
    var estimatedBytes = 0L
    entries.forEach { (name, bytes) ->
        val entryBytes = bytes.size + bytes.size / 100 + 2L * name.length + ZIP_ENTRY_OVERHEAD_BYTES
        check(entryBytes + ZIP_END_BYTES <= partBytes) { "Resolver test suite entry $name does not fit in a $partBytes-byte part" }
        if (parts.last().isNotEmpty() && estimatedBytes + entryBytes + ZIP_END_BYTES > partBytes) {
            parts += linkedMapOf<String, ByteArray>()
            estimatedBytes = 0
        }
        parts.last()[name] = bytes
        estimatedBytes += entryBytes
    }
    return parts.map(::writeResolverTestSuiteArchive).onEach { part ->
        check(part.size <= partBytes) { "Resolver test suite part of ${part.size} bytes exceeds $partBytes" }
    }
}

// Local and central headers plus a data descriptor per entry, and the end-of-archive record; deflate may grow gzipped data by under 1%.
private const val ZIP_ENTRY_OVERHEAD_BYTES = 128
private const val ZIP_END_BYTES = 64

// A fixed entry time keeps regenerated archives byte-identical.
internal fun writeResolverTestSuiteArchive(entries: Map<String, ByteArray>): ByteArray =
    ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { timeLocal = ARCHIVE_ENTRY_TIME })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

private val ARCHIVE_ENTRY_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

private fun ResolverTestSuiteDefinition.readEntry(
    entries: Map<String, ByteArray>,
    entry: String,
): ByteArray =
    requireNotNull(entries[entry]) {
        "Resolver test suite $id archives ${suiteArchiveResource(id)} hold no entry $entry; $ARCHIVE_HINT"
    }

// Generation order is fixed by a generation's seed, so a batch keeps its pool ordinal when other batches are pruned from the manifest.
internal fun resolverTestBatchId(ordinal: Int): String = "b" + ordinal.toString().padStart(3, '0')

internal fun resolverTestBatchOrdinal(batchId: String): Int = requireNotNull(batchId.removePrefix("b").toIntOrNull()) { "Pool batch ID $batchId is not b<ordinal>" }

internal data class NumberedResolverTestBatch(
    val id: String,
    val batch: ResolverTestBatch,
    val ids: ResolverTestShardIds,
)

/** Deduplicates and numbers generated batches exactly as they are stored. */
internal fun ResolverTestSuiteDefinition.numberBatches(generated: List<ResolverTestBatch>): List<NumberedResolverTestBatch> =
    generated.mapIndexed { offset, generatedBatch ->
        val batch = ResolverTestShardCodec.withoutDuplicates(generatedBatch)
        val batchId = resolverTestBatchId(offset + 1)
        val ids = ResolverTestShardCodec.ids(batch)
        check(ids.queries.allDistinct()) {
            "Suite $id batch $batchId has distinct queries whose IDs collide"
        }
        NumberedResolverTestBatch(batchId, batch, ids)
    }

/** Checks one numbered batch, stamping each case with its stable [ResolverTestCaseId]; [selectiveNodeResolvers] is the batch's own generator setting. */
internal suspend fun ResolverTestSuiteDefinition.checkNumberedBatch(
    batchId: String,
    batch: ResolverTestBatch,
    queries: List<String>,
    schemaIndex: Int,
    seed: Long,
    counts: TestCaseCount,
    selected: ResolverTestCaseCoordinate? = null,
    selectiveResolvers: Boolean = true,
    selectiveNodeResolvers: Boolean = config[SelectiveNodeResolversEnabled],
    property: suspend (TestWorld, ResolverTestCase) -> Unit,
): Int =
    checkResolverTestBatch(
        batch = batch,
        schemaIndex = schemaIndex,
        execution = ResolverTestExecution(counts, selected),
        profile = profile,
        seed = seed,
        selectiveNodeResolvers = selectiveNodeResolvers,
        fieldCheckerMode = fieldCheckerMode,
        typeCheckerMode = typeCheckerMode,
        selectiveResolvers = selectiveResolvers,
        testCaseId = { queryIndex -> ResolverTestCaseId(id, batchId, queries[queryIndex - 1]).toString() },
        property = property,
    )

// bazel run executes in the runfiles tree; relative paths are meant relative to the caller's workspace.
internal fun workspaceRelativePath(path: String): Path = System.getenv("BUILD_WORKSPACE_DIRECTORY")?.let { Path.of(it).resolve(path) } ?: Path.of(path)

private const val MATERIALIZE_HINT =
    "materialize it with ./gradlew :core:engine:runtime2:materializeResolverTestSuites -PresolverTestSuites=<suite-id> " +
        "or bazel run //projects/viaduct/oss/core/engine/runtime2:materialize_resolver_test_suites -- <suite-id>"

private const val ARCHIVE_HINT = "stored suites are not regenerated to repair them; restore the archive from version control"

private const val FORMAT_HINT = "a change that bumps the format must convert the stored archives in place rather than regenerate them"
