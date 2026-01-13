package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import java.io.FileInputStream
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
import viaduct.tenant.codegen.bytecode.CodeGenArgs
import viaduct.tenant.codegen.bytecode.GRTClassFilesBuilderBase
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.graphql.bridge.ScopedSchemaFilter
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteDirectories
import viaduct.tenant.codegen.util.shouldUseBinarySchema
import viaduct.utils.timer.Timer

/**
 * This class is used to generate the modern schama object.
 * It doesnt require a version(It will use GRTClassFilesBuilder).
 */
class SchemaObjectsBytecode : CliktCommand() {
    // Files & Directories
    private val generatedDir: File by option("--generated_directory")
        .file(mustExist = false, canBeFile = false).required()

    private val outputArchive: File? by option("--output_archive")
        .file(mustExist = false, canBeDir = false)

    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()

    private val binarySchemaFile: File by option("--binary_schema_file")
        .file(mustExist = true, canBeDir = false).required()

    private val moduleName: String? by option("--module_name")

    private val pkgForGeneratedClasses: String by option("--pkg_for_generated_classes")
        .default("com.airbnb.viaduct.schema.generated")

    // if a pkg is provided in a file, can happen when it's not possible to pass a package info string
    // without parsing some src files
    private val pkgForGeneratedClassesAsFile: File? by option("--pkg_for_generated_classes_as_file")
        .file(mustExist = false, canBeDir = false)

    private val workerNumber: Int by option("--bytecode_worker_number").int().default(0)
    private val workerCount: Int by option("--bytecode_worker_count").int().default(1)

    private val appliedScopes: List<String>? by option("--applied_scopes").split(",")

    private val includeIneligibleForTestingOnly: Boolean by option("--include_ineligible_for_testing_only")
        .flag("--exclude_ineligible", default = false)
        .help(
            "By default we will not generate Object types that are ineligible. This flag can " +
                "be used for tests only to generate all object types, including ineligible ones."
        )
    val compilationSchema: File? by option("--compilation_schema").file(mustExist = false, canBeDir = false)
    val compilationSchemaBinary: File? by option("--binary_compilation_schema").file(mustExist = false, canBeDir = false)
    val flagFile: File by option("--flag_file").file(mustExist = true, canBeDir = false).required()

    override fun run() {
        if (generatedDir.exists()) generatedDir.deleteRecursively()
        generatedDir.mkdirs()
        val scopeSet = appliedScopes?.toSet()

        val timer = Timer()
        val schema = timer.time("schemaFromFiles") {
            if (shouldUseBinarySchema(flagFile)) {
                val schemaFile = if (compilationSchemaBinary != null) {
                    compilationSchemaBinary
                } else {
                    binarySchemaFile
                }
                readBSchema(FileInputStream(schemaFile!!))
            } else {
                val schemaFiles = if (compilationSchema != null) {
                    listOf(compilationSchema!!)
                } else {
                    schemaFiles
                }
                val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(schemaFiles) }
                GJSchemaRaw.fromRegistry(typeDefRegistry, timer)
            }
        }.let {
            if (scopeSet.isNullOrEmpty()) {
                it
            } else {
                it.filter(ScopedSchemaFilter(scopeSet))
            }
        }

        val codegenArgs = CodeGenArgs(
            moduleName = moduleName,
            pkgForGeneratedClasses = pkgForGeneratedClassesAsFile?.readText()?.trim() ?: pkgForGeneratedClasses,
            includeIneligibleTypesForTestingOnly = includeIneligibleForTestingOnly,
            excludeCrossModuleFields = scopeSet.isNullOrEmpty(),
            javaTargetVersion = null,
            workerNumber = workerNumber,
            workerCount = workerCount,
            timer = timer,
            baseTypeMapper = ViaductBaseTypeMapper(schema),
        )

        val grtBuilder = GRTClassFilesBuilderBase.builderFrom(codegenArgs)

        timer.time("generateBytecodeImpl") {
            grtBuilder.addAll(schema)
        }

        timer.time("generateClassfiles") {
            grtBuilder.buildClassfiles(generatedDir)
        }

        timer.time("fileManipulation") {
            outputArchive?.let {
                it.zipAndWriteDirectories(generatedDir)
                generatedDir.deleteRecursively()
            }
        }

        if (moduleName == "replace with module name (e.g. 'presentation') to report timing via exception") {
            timer.reportViaException()
        }
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = SchemaObjectsBytecode().main(args)
    }
}
