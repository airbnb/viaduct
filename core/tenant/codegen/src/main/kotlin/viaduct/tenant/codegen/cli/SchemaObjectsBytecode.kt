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
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.bytecode.CodeGenArgs
import viaduct.tenant.codegen.bytecode.GRTClassFilesBuilderBase
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.graphql.schema.ScopedSchemaFilter
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteChildrenAsRoot
import viaduct.tenant.codegen.util.hasBinarySchemaFlag
import viaduct.tenant.codegen.util.shouldUseBinarySchema
import viaduct.utils.timer.Timer

/**
 * This class is used to generate the modern schama object.
 * It doesnt require a version(It will use GRTClassFilesBuilder).
 */
class SchemaObjectsBytecode : CliktCommand() {
    companion object {
        private const val DEFAULT_MANIFEST = "Manifest-Version: 1.0\r\nCreated-By: singlejar\r\n\r\n"
    }

    // Files & Directories
    private val generatedDir: File by option("--generated_directory")
        .file(mustExist = false, canBeFile = false).required()

    private val outputArchive: File? by option("--output_archive")
        .file(mustExist = false, canBeDir = false)

    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()

    private val binarySchemaFile: File? by option("--binary_schema_file")
        .file(mustExist = true, canBeDir = false)

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
    val compilationSchemaBinary: File? by option("--compilation_schema_binary").file(mustExist = false, canBeDir = false)
    val flagFile: File? by option("--flag_file").file(mustExist = true, canBeDir = false)

    private val allowExtObjectSetters: Boolean by option("--allow_ext_object_setters").flag()

    override fun run() {
        // Validation:
        // - If flag file has enable_binary_schema (True or False): --binary_schema_file required
        // - If flag file missing or doesn't mention enable_binary_schema: --binary_schema_file
        //   optional, but at least one of --schema_files or --binary_schema_file must be present
        if (hasBinarySchemaFlag(flagFile)) {
            require(binarySchemaFile != null) {
                "--binary_schema_file is required when --flag_file contains enable_binary_schema"
            }
        } else {
            require(binarySchemaFile != null || schemaFiles.isNotEmpty()) {
                "At least one of --schema_files or --binary_schema_file must be provided"
            }
        }

        if (generatedDir.exists()) generatedDir.deleteRecursively()
        generatedDir.mkdirs()
        val scopeSet = appliedScopes?.toSet()

        val timer = Timer()
        val useBinary = shouldUseBinarySchema(flagFile)
        val schema = timer.time("schemaFromFiles") {
            if (useBinary) {
                val schemaFile = compilationSchemaBinary ?: binarySchemaFile
                ViaductSchema.fromBinaryFile(schemaFile!!)
            } else {
                val schemaFileList = if (compilationSchema != null) {
                    listOf(compilationSchema!!)
                } else {
                    schemaFiles
                }
                ViaductSchema.fromTypeDefinitionRegistry(schemaFileList, timer)
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
            allowExtObjectSetters = allowExtObjectSetters
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
                val manifestDir = generatedDir.resolve("META-INF").apply { mkdirs() }
                manifestDir.resolve("MANIFEST.MF").writeText(DEFAULT_MANIFEST)
                it.zipAndWriteChildrenAsRoot(generatedDir)
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
