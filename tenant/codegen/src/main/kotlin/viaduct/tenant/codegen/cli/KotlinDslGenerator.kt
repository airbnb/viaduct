package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.dsl.DslFilesBuilder
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteDirectories
import viaduct.utils.timer.Timer

/**
 * CLI command for generating Kotlin DSL query builders from GraphQL schema.
 *
 * This command generates type-safe Kotlin DSL code that allows users to build
 * GraphQL queries programmatically without string manipulation.
 *
 * Example generated usage:
 * ```kotlin
 * val query = query(name = "GetUser") {
 *     greeting
 *     author
 *     user(id = "123") {
 *         name
 *         email
 *     }
 * }
 * ```
 *
 * This is primarily designed to be invoked by the Viaduct Gradle plugin.
 */
class KotlinDslGenerator : CliktCommand() {
    private val outputArchive: File? by option("--output_archive")
        .file(mustExist = false, canBeDir = false)

    private val generatedDir: File by option("--generated_directory")
        .file(mustExist = false, canBeFile = false).required()

    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()

    private val pkgForGeneratedClasses: String by option("--pkg_for_generated_classes")
        .default("viaduct.api.dsl")

    override fun run() {
        if (generatedDir.exists()) generatedDir.deleteRecursively()
        generatedDir.mkdirs()

        val timer = Timer()

        val schema = timer.time("schemaFromFiles") {
            val typeDefRegistry = timer.time("readTypesFromFiles") {
                readTypesFromFiles(schemaFiles)
            }
            GJSchemaRaw.fromRegistry(typeDefRegistry, timer)
        }

        val baseTypeMapper = ViaductBaseTypeMapper(schema)

        val dslBuilder = DslFilesBuilder(
            pkg = pkgForGeneratedClasses,
            outputDir = generatedDir,
            baseTypeMapper = baseTypeMapper
        )

        timer.time("generateDslFiles") {
            dslBuilder.generate(schema)
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
        fun main(args: Array<String>) = KotlinDslGenerator().main(args)
    }
}
