package viaduct.x.javaapi.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import viaduct.x.javaapi.codegen.JavaGRTsCodegen
import viaduct.x.javaapi.codegen.JavaResolversCodegen

/**
 * CLI entry point for generating Java GRTs (GraphQL Representational Types) and resolver base
 * classes from GraphQL schemas.
 *
 * Parameters:
 * - grtOutputDir: directory for GRT files (written to package subdirs)
 * - grtPackage: package name for GRT types
 * - resolverGeneratedDir: directory for resolver files (written to package subdirs)
 * - tenantPackage: package name for resolver bases ({tenantPackage}.resolverbases)
 * - grtOutputArchive: optional srcjar path to zip GRT output directory into
 * - resolverOutputArchive: optional srcjar path to zip resolver output directory into
 * - includeRootTypes: if true, include Query/Mutation/Subscription GRTs
 *
 * GRTs and Resolvers are generated independently using separate codegen classes:
 * - [JavaGRTsCodegen] for GRT types (enums, objects, inputs, interfaces, unions)
 * - [JavaResolversCodegen] for resolver base classes
 */
class JavaGRTsGenerator : CliktCommand(
    name = "java-grts-generator",
    help = "Generates Java GRTs (GraphQL Representational Types) and resolvers from GraphQL schema files"
) {
    private val schemaFiles: List<File> by option("--schema_files", help = "Comma-separated list of GraphQL schema files")
        .file(mustExist = true, canBeDir = false)
        .split(",")
        .required()

    private val grtOutputDir: File by option("--grt_output_dir", help = "Output directory for generated GRT files (written to package subdirs)")
        .file(mustExist = false, canBeFile = false)
        .required()

    private val grtPackage: String? by option("--grt_package", help = "Java package name for generated GRT types")

    private val grtPackageFile: File? by option("--grt_package_file", help = "File containing the Java package name for generated GRT types")
        .file(mustExist = false, canBeDir = false)

    private val resolverGeneratedDir: File by option("--resolver_generated_dir", help = "Output directory for resolver files (written to package subdirs)")
        .file(mustExist = false, canBeFile = false)
        .required()

    private val tenantPackage: String? by option("--tenant_package", help = "Java package name for resolver bases (uses {tenantPackage}.resolverbases)")

    private val tenantPackageFile: File? by option("--tenant_package_file", help = "File containing the Java package name for resolver bases")
        .file(mustExist = false, canBeDir = false)

    private val grtOutputArchive: File? by option("--grt_output_archive", help = "Optional output srcjar path for generated GRT files")
        .file(mustExist = false, canBeDir = false)

    private val resolverOutputArchive: File? by option("--resolver_output_archive", help = "Optional output srcjar path for generated resolver files")
        .file(mustExist = false, canBeDir = false)

    private val includeRootTypes: Boolean by option("--include_root_types", help = "If set, include Query/Mutation/Subscription GRTs")
        .flag()

    private val verbose: Boolean by option("--verbose", help = "Print generation results")
        .flag()

    override fun run() {
        val resolvedGrtPackage = grtPackageFile?.readText()?.trim()
            ?: grtPackage
            ?: error("Either --grt_package or --grt_package_file must be provided")
        val resolvedTenantPackage = tenantPackageFile?.readText()?.trim()
            ?: tenantPackage
            ?: resolvedGrtPackage

        // Ensure output directories exist
        grtOutputDir.mkdirs()
        resolverGeneratedDir.mkdirs()

        // Generate GRTs (enums, objects, inputs, interfaces, unions)
        val grtsCodegen = JavaGRTsCodegen()
        val grtsResult = grtsCodegen.generate(schemaFiles, grtOutputDir, resolvedGrtPackage, includeRootTypes)

        // Remove Mutation.java and Subscription.java when includeRootTypes is true:
        // these types use `_` as a field name which is invalid in Java 9+.
        if (includeRootTypes) {
            val packageSubdir = resolvedGrtPackage.replace('.', File.separatorChar)
            listOf("Mutation.java", "Subscription.java").forEach { name ->
                File(grtOutputDir, "$packageSubdir/$name").delete()
            }
        }

        // Generate Resolvers (separate step)
        val resolversCodegen = JavaResolversCodegen()
        val resolversResult = resolversCodegen.generate(schemaFiles, resolverGeneratedDir, resolvedGrtPackage, resolvedTenantPackage)

        // Archive outputs into srcjars if requested
        grtOutputArchive?.let { archive ->
            zipDirectory(grtOutputDir, archive)
            grtOutputDir.deleteRecursively()
        }
        resolverOutputArchive?.let { archive ->
            zipDirectory(resolverGeneratedDir, archive)
            resolverGeneratedDir.deleteRecursively()
        }

        if (verbose) {
            for (file in grtsResult.generatedFiles()) {
                echo("Generated GRT: $file")
            }
            for (file in resolversResult.generatedFiles()) {
                echo("Generated Resolver: $file")
            }

            val totalCount = grtsResult.totalCount() + resolversResult.resolverFileCount()
            echo("Generated $totalCount types:")
            echo("  GRT output: ${grtOutputDir.absolutePath}")
            echo("  Resolver output: ${resolverGeneratedDir.absolutePath}")
            echo("  - ${grtsResult.enumCount()} enum(s)")
            echo("  - ${grtsResult.objectCount()} object(s)")
            echo("  - ${grtsResult.inputCount()} input(s)")
            echo("  - ${grtsResult.interfaceCount()} interface(s)")
            echo("  - ${grtsResult.unionCount()} union(s)")
            echo("  - ${grtsResult.argumentCount()} argument type(s)")
            echo("  - ${resolversResult.resolverFileCount()} resolver file(s) containing ${resolversResult.resolverCount()} resolver(s)")
        }
    }

    /** Zips the contents of [sourceDir] into [archive] as a srcjar. */
    private fun zipDirectory(
        sourceDir: File,
        archive: File
    ) {
        archive.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(archive)).use { out ->
            Files.walk(sourceDir.toPath()).map(Path::toFile).sorted().forEach { file ->
                val relativePath = file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                if (relativePath.isEmpty()) return@forEach
                val entry = ZipEntry(if (file.isDirectory) "$relativePath/" else relativePath)
                entry.lastModifiedTime = FileTime.fromMillis(0)
                out.putNextEntry(entry)
                if (file.isFile) {
                    Files.copy(file.toPath(), out)
                }
                out.closeEntry()
            }
        }
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = JavaGRTsGenerator().main(args)
    }
}
