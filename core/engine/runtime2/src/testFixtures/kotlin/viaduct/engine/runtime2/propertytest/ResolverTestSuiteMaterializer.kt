@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.generateResolverTestBatches
import viaduct.utils.string.sha256Hash

object ResolverTestSuiteMaterializer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Usage: ResolverTestSuiteMaterializer <resources-root> [suite-id...]"
        }
        val resourcesRoot =
            System.getenv("BUILD_WORKSPACE_DIRECTORY")
                ?.let { workspace -> Path.of(workspace).resolve(args.first()) }
                ?: Path.of(args.first())
        val selectedIds = args.drop(1).toSet()
        ResolverTestSuiteDefinition.entries
            .filter { definition -> selectedIds.isEmpty() || definition.id in selectedIds }
            .forEach { definition -> materialize(resourcesRoot, definition) }
    }

    fun materialize(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
    ) {
        val batches = runBlocking { generateResolverTestBatches(definition.counts, definition.config, definition.seed) }
        write(resourcesRoot, definition, batches)
    }

    /** Stores every batch, including batches that share a schema; their SDL is written once. */
    fun write(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
        generated: List<ResolverTestBatch>,
    ) {
        val manifestPath = resourcesRoot.resolve(manifestResource(definition.id))
        val suiteDirectory = manifestPath.parent
        Files.createDirectories(suiteDirectory)
        Files.list(suiteDirectory).use { files -> files.forEach(Files::delete) }
        val sdlBySchema = linkedMapOf<String, ByteArray>()
        val batches =
            generated.mapIndexed { offset, generatedBatch ->
                val batch = ResolverTestShardCodec.withoutDuplicates(generatedBatch)
                val batchId = resolverTestBatchId(offset + 1)
                val ids = ResolverTestShardCodec.ids(batch)
                check(ids.registries.allDistinct() && ids.queries.allDistinct()) {
                    "Suite ${definition.id} batch $batchId has distinct registries or queries whose IDs collide"
                }
                val shard = ResolverTestShardCodec.encode(batchId, batch)
                val reencoded = ResolverTestShardCodec.encode(batchId, ResolverTestShardCodec.decode(batchId, shard.sdl, shard.json))
                check(reencoded.sdl.contentEquals(shard.sdl) && reencoded.json.contentEquals(shard.json)) {
                    "Suite ${definition.id} batch $batchId does not round-trip through the shard codec"
                }
                check(sdlBySchema.getOrPut(ids.schema) { shard.sdl }.contentEquals(shard.sdl)) {
                    "Suite ${definition.id} has distinct schemas whose IDs collide at ${ids.schema}"
                }
                Files.write(resourcesRoot.resolve(shardResource(definition.id, batchId)), shard.json)
                ResolverTestSuiteBatch(batchId, ids.schema, shard.json.sha256Hash(), ids.registries, ids.queries)
            }
        sdlBySchema.forEach { (schemaId, sdl) -> Files.write(resourcesRoot.resolve(sdlResource(definition.id, schemaId)), sdl) }
        val schemas = sdlBySchema.map { (schemaId, sdl) -> ResolverTestSuiteSchema(schemaId, sdl.sha256Hash()) }
        Files.writeString(manifestPath, PropertyTestJson.write(definition.manifest(schemas, batches)))
    }
}
