@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
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
        val manifestPath = resourcesRoot.resolve(manifestResource(definition.id))
        val suiteDirectory = manifestPath.parent
        Files.createDirectories(suiteDirectory)
        Files.list(suiteDirectory).use { files -> files.forEach(Files::delete) }
        val batches =
            runBlocking {
                generateResolverTestBatches(definition.counts, definition.config, definition.seed)
            }
        val shards =
            batches.mapIndexed { offset, batch ->
                val schemaIndex = offset + 1
                val shard = ResolverTestShardCodec.encode(schemaIndex, batch)
                val reencoded = ResolverTestShardCodec.encode(schemaIndex, ResolverTestShardCodec.decode(schemaIndex, shard.sdl, shard.json))
                check(reencoded.sdl.contentEquals(shard.sdl) && reencoded.json.contentEquals(shard.json)) {
                    "Suite ${definition.id} schema $schemaIndex does not round-trip through the shard codec"
                }
                Files.write(resourcesRoot.resolve(sdlResource(definition.id, schemaIndex)), shard.sdl)
                Files.write(resourcesRoot.resolve(shardResource(definition.id, schemaIndex)), shard.json)
                shard
            }
        val manifest = definition.manifest(shards.map { shard -> shard.sdl.sha256Hash() }, shards.map { shard -> shard.json.sha256Hash() })
        Files.writeString(manifestPath, PropertyTestJson.write(manifest))
    }
}
