package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.io.FileInputStream
import viaduct.graphql.schema.binary.readBSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.cfg.BUILD_TIME_MODULE_EXTRACTOR
import viaduct.tenant.codegen.kotlingen.Args
import viaduct.tenant.codegen.kotlingen.generateFieldResolvers
import viaduct.tenant.codegen.kotlingen.generateNodeResolvers
import viaduct.tenant.codegen.util.ZipUtil.zipAndWriteDirectories
import viaduct.tenant.codegen.util.shouldUseBinarySchema

/**
 * Entry point to Modern Bazel code generation for a viaduct tenant.
 */
class ViaductGenerator : CliktCommand() {
    // modern module args
    private val tenantPkg: String by option("--tenant_pkg").required()
    private val modernModuleGeneratedDir: File? by option("--modern_module_generated_directory")
        .file(mustExist = false, canBeFile = false)
    private val modernModuleOutputArchive: File? by option("--modern_module_output_archive")
        .file(mustExist = false, canBeDir = false)
    private val metainfGeneratedDir: File? by option("--metainf_generated_directory")
        .file(mustExist = false, canBeFile = false)
    private val metainfOutputArchive: File? by option("--metainf_output_archive")
        .file(mustExist = false, canBeDir = false)

    // schema args
    private val schemaFiles: List<File> by option("--schema_files")
        .file(mustExist = true, canBeDir = false).split(",").required()
    private val binarySchemaFile: File by option("--binary_schema_file")
        .file(mustExist = true, canBeDir = false).required()
    private val flagFile: File by option("--flag_file")
        .file(mustExist = true, canBeDir = false).required()

    // resolver args
    private val resolverGeneratedDir: File by option("--resolver_generated_directory")
        .file(mustExist = false, canBeFile = false).required()
    private val resolverOutputArchive: File? by option("--resolver_output_archive")
        .file(mustExist = false, canBeDir = false)

    private val tenantPackagePrefix: String? by option("--tenant_package_prefix")
    private val tenantPackagePrefixInFile: File? by option("--tenant_package_prefix_in_file")
        .file(mustExist = false, canBeDir = false)
    private val tenantFromSourceNameRegex: String? by option("--tenant_from_source_name_regex")
        .convert { unquoteRegexPattern(it) }

    private val isFeatureAppTest: Boolean by option("--isFeatureAppTest").flag()

    override fun run() {
        val archivesNullCount = listOf(modernModuleOutputArchive, metainfOutputArchive, resolverOutputArchive).count { it != null }
        require(archivesNullCount == 0 || archivesNullCount == 3) {
            "Provided directories to store the archives must be either null or non-null together"
        }

        val schema = if (shouldUseBinarySchema(flagFile)) {
            readBSchema(FileInputStream(binarySchemaFile))
        } else {
            GJSchemaRaw.fromFiles(schemaFiles)
        }
        // TODO(jimmy): Remove this global mutable state, see https://docs.google.com/document/d/18FKs13huMY3JyslnO11_V_WtYPcSA7Xb_vNQAf79yP0/edit?tab=t.0#heading=h.a24h0oe8myl2
        cfg.moduleExtractor = if (!tenantFromSourceNameRegex.isNullOrEmpty()) {
            Regex(tenantFromSourceNameRegex!!)
        } else {
            BUILD_TIME_MODULE_EXTRACTOR
        }

        val tenantPackage = tenantPackagePrefixInFile?.readText()?.trim() ?: "$tenantPackagePrefix.${tenantPkg.replace("_", ".")}"
        val packagePrefixForTenant = tenantPackagePrefixInFile?.readText()?.trim() ?: tenantPackagePrefix!!
        val grtPackages = if (isFeatureAppTest) tenantPackage else "viaduct.api.grts"

        val args = Args(
            tenantPackage = tenantPackage,
            tenantPackagePrefix = packagePrefixForTenant,
            tenantName = tenantPkg,
            grtPackage = grtPackages,
            modernModuleGeneratedDir = modernModuleGeneratedDir,
            metainfGeneratedDir = metainfGeneratedDir,
            resolverGeneratedDir = resolverGeneratedDir,
            isFeatureAppTest = isFeatureAppTest,
            baseTypeMapper = viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper(schema)
        )

        if (resolverGeneratedDir.exists()) resolverGeneratedDir.deleteRecursively()
        resolverGeneratedDir.mkdirs()
        modernModuleGeneratedDir?.let { dir ->
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
        }
        metainfGeneratedDir?.let { dir ->
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
        }

        schema.generateFieldResolvers(args)
        schema.generateNodeResolvers(args)

        resolverOutputArchive?.let {
            it.zipAndWriteDirectories(resolverGeneratedDir)
            resolverGeneratedDir.deleteRecursively()
        }
        modernModuleOutputArchive?.let {
            it.zipAndWriteDirectories(modernModuleGeneratedDir!!)
            modernModuleGeneratedDir!!.deleteRecursively()
        }
        metainfOutputArchive?.let {
            it.zipAndWriteDirectories(metainfGeneratedDir!!)
            metainfGeneratedDir!!.deleteRecursively()
        }
        // TODO(https://app.asana.com/1/150975571430/project/1207604899751448/task/1210764159508822?focus=true): Remove this global mutable state, see https://docs.google.com/document/d/18FKs13huMY3JyslnO11_V_WtYPcSA7Xb_vNQAf79yP0/edit?tab=t.0#heading=h.a24h0oe8myl2
        cfg.moduleExtractor = BUILD_TIME_MODULE_EXTRACTOR
    }

    private fun unquoteRegexPattern(pattern: String) =
        when {
            pattern.startsWith('"') && pattern.endsWith('"') -> pattern.substring(1, pattern.length - 1)
            pattern.startsWith("'") && pattern.endsWith("'") -> pattern.substring(1, pattern.length - 1)
            else -> pattern
        }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = ViaductGenerator().main(args)
    }
}
