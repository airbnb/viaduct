package viaduct.tenant.codegen.bytecode

import com.google.common.io.Resources
import graphql.schema.idl.SchemaParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.utils.timer.Timer

/**
 * End-to-end benchmark for the bytecode code generation pipeline.
 *
 * Measures the full codegen time using large-schema-4 (~18,000 types).
 * The measured pipeline is:
 * create [GRTClassFilesBuilder] -> [GRTClassFilesBuilderBase.addAll] ->
 * [GRTClassFilesBuilderBase.buildClassfiles] (writing .class files to disk).
 *
 * This establishes a baseline before changing getters from suspend to synchronous.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1, jvmArgs = ["--add-opens", "java.base/java.lang=ALL-UNNAMED"])
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class CodegenBenchmark {
    private lateinit var schema: ViaductSchema

    companion object {
        private const val PKG = "benchmark.codegen"
    }

    @Setup(Level.Trial)
    fun setup() {
        val resourcePath = "large-schema-4/large-schema-4.graphqls.zip"
        val zipResource = Resources.getResource(resourcePath)
        val tempFile = File.createTempFile("large-schema-4", ".graphqls")
        try {
            ZipInputStream(zipResource.openStream()).use { zipStream ->
                zipStream.nextEntry
                tempFile.outputStream().use { output ->
                    zipStream.copyTo(output)
                }
            }
            val registry = SchemaParser().parse(tempFile)
            schema = ViaductSchema.fromTypeDefinitionRegistry(registry)
        } finally {
            tempFile.delete()
        }
    }

    @Benchmark
    fun codegenEndToEnd(bh: Blackhole) {
        val outputDir = Files.createTempDirectory("codegen-benchmark").toFile()
        try {
            val builder = createGRTBuilder(schema, PKG)
            builder.addAll(schema)
            builder.buildClassfiles(outputDir)
            bh.consume(builder)
        } finally {
            outputDir.deleteRecursively()
        }
    }

    private fun createGRTBuilder(
        schema: ViaductSchema,
        pkg: String
    ): GRTClassFilesBuilder {
        val args = CodeGenArgs(
            moduleName = null,
            pkgForGeneratedClasses = pkg,
            includeIneligibleTypesForTestingOnly = false,
            excludeCrossModuleFields = false,
            javaTargetVersion = null,
            workerNumber = 0,
            workerCount = 1,
            timer = Timer(),
            baseTypeMapper = ViaductBaseTypeMapper(schema),
        )
        return GRTClassFilesBuilder(args)
    }
}
