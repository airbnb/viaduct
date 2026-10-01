package viaduct.engine.runtime2.propertytest

import java.nio.file.Files
import java.nio.file.Path
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.GeneratorConfigData
import viaduct.engine.runtime2.resolution.ResolutionBroadStressProfile
import viaduct.engine.runtime2.resolution.withLargeDeepResolutionWorlds

object GeneratorConfigMaterializer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) {
            "Usage: GeneratorConfigMaterializer <resources-root>"
        }
        val generatorData = generatorConfigData()
        val generatorDirectory =
            Path.of(args.single()).resolve("viaduct/engine/runtime2/property-tests/generator-configs")
        Files.createDirectories(generatorDirectory)
        Files.list(generatorDirectory).use { files ->
            files
                .filter { file -> file.fileName.toString().endsWith(".json") }
                .forEach(Files::delete)
        }
        generatorData.forEach { (id, data) ->
            write(generatorDirectory.resolve("$id.json"), data)
        }
        write(
            generatorDirectory.resolve("index.json"),
            GeneratorConfigResourceIndex(
                formatVersion = GENERATOR_CONFIG_INDEX_FORMAT_VERSION,
                resources =
                    generatorData.keys.map { id ->
                        "/viaduct/engine/runtime2/property-tests/generator-configs/$id.json"
                    },
            ),
        )
    }
}

private fun generatorConfigData(): Map<String, GeneratorConfigData> =
    linkedMapOf<String, GeneratorConfigData>().apply {
        ResolutionBroadStressProfile.entries.forEach { profile ->
            add("resolution-${profile.id}-standard", profile.config)
            val largeDeep = profile.config.withLargeDeepResolutionWorlds()
            if (profile != ResolutionBroadStressProfile.SYMBOLIC_IDENTITY) {
                add("resolution-${profile.id}-large-deep", largeDeep)
            }
            add(
                "resolution-${profile.id}-large-deep-low-duplicates",
                largeDeep + (DuplicateSelectionWeight to 0.1),
            )
        }
    }

private fun MutableMap<String, GeneratorConfigData>.add(
    id: String,
    config: Config,
) {
    val data = GeneratorConfigData.from(id, config)
    check(putIfAbsent(id, data) == null) {
        "Duplicate generator profile $id"
    }
}

private fun write(
    path: Path,
    value: Any,
) {
    Files.createDirectories(path.parent)
    Files.writeString(path, PropertyTestJson.write(value))
}
