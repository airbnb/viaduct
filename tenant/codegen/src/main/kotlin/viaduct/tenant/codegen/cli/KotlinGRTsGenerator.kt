package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.io.FileInputStream
import viaduct.apiannotations.TestingApi
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.KotlinCodeGenArgs
import viaduct.tenant.codegen.kotlingen.bytecode.KotlinGRTFilesBuilder
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteDirectories
import viaduct.tenant.codegen.util.shouldUseBinarySchema
import viaduct.utils.timer.Timer

/**
 * CLI command for generating Kotlin source code for GraphQL Runtime Types (GRTs).
 *
 * This command generates Kotlin source files for GRTs as an alternative to the existing
 * bytecode generation approach. It reads GraphQL schema definition files and produces
 * corresponding Kotlin data classes, sealed classes, enums, and interfaces that provide
 * type-safe representations of GraphQL types.
 *
 * This is primarily designed to be invoked by the Viaduct Gradle plugin to enable
 * source-level debugging, IDE support, and easier development workflows compared
 * to runtime bytecode generation.
 **/
@TestingApi
class KotlinGRTsGenerator : CliktCommand() {
    private val outputArchive: File? by option("--output_archive")
        .file(mustExist = false, canBeDir = false)
    private val generatedDir: File by option("--generated_directory")
        .file(mustExist = false, canBeFile = false).required()
    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()
    private val binarySchemaFile: File by option("--binary_schema_file")
        .file(mustExist = true, canBeDir = false).required()
    private val flagFile: File by option("--flag_file")
        .file(mustExist = true, canBeDir = false).required()
    private val pkgForGeneratedClasses: String by option("--pkg_for_generated_classes")
        .default("com.airbnb.viaduct.schema.generated")

    override fun run() {
        val useBinarySchema = shouldUseBinarySchema(flagFile)
        if (generatedDir.exists()) generatedDir.deleteRecursively()
        generatedDir.mkdirs()

        val timer = Timer()

        val schema = timer.time("schemaFromFiles") {
            if (useBinarySchema) {
                readBSchema(FileInputStream(binarySchemaFile))
            } else {
                val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(schemaFiles) }
                GJSchemaRaw.fromRegistry(typeDefRegistry, timer)
            }
        }

        val args = KotlinCodeGenArgs(
            pkgForGeneratedClasses = pkgForGeneratedClasses,
            dirForOutput = generatedDir,
            timer = timer,
            baseTypeMapper = ViaductBaseTypeMapper(schema),
        )

        // Modern builds use ViaductBaseTypeMapper (set by default in cfg)
        val grtBuilder = KotlinGRTFilesBuilder.builderFrom(args)

        timer.time("generateBytecodeImpl") {
            grtBuilder.addAll(schema)
        }

        timer.time("fileManipulation") {
            outputArchive?.let {
                it.zipAndWriteDirectories(generatedDir)
                generatedDir.deleteRecursively()
            }
        }
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = KotlinGRTsGenerator().main(args)
    }
}
