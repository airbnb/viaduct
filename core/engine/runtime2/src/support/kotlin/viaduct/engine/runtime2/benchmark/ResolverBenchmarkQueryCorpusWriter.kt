package viaduct.engine.runtime2.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.arbitrary.resolverBenchmarkOverheadQueryConfig

object ResolverBenchmarkQueryCorpusWriter {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 5) {
            "Expected arguments: <schema> <registry> <output> <query-count> <query-seed>"
        }
        val schemaPath = Path.of(arguments[0])
        val registryPath = Path.of(arguments[1])
        val outputPath = Path.of(arguments[2])
        val queryCount = arguments[3].toInt()
        val querySeed = arguments[4].toLong()
        val corpus =
            ResolverBenchmarkCorpus.decode(
                schemaSDL = Files.readString(schemaPath),
                registryJson =
                    Files.newInputStream(registryPath).use { stream ->
                        val input = if (registryPath.toString().endsWith(".gz")) GZIPInputStream(stream) else stream
                        input.bufferedReader().use { reader -> reader.readText() }
                    },
            )
        val querySources =
            corpus
                .generateQueries(
                    count = queryCount,
                    config = resolverBenchmarkOverheadQueryConfig(),
                    seed = querySeed,
                ).map { query -> query.source }
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(
            outputPath,
            ResolverBenchmarkQueryCorpus
                .create(
                    generationSeed = querySeed,
                    querySources = querySources,
                ).encode(),
        )
        println("Wrote $queryCount resolver benchmark queries to $outputPath")
    }
}
