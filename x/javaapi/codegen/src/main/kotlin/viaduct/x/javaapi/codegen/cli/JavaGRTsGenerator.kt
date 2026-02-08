package viaduct.x.javaapi.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
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

    private val grtPackage: String by option("--grt_package", help = "Java package name for generated GRT types")
        .required()

    private val resolverGeneratedDir: File by option("--resolver_generated_dir", help = "Output directory for resolver files (written to package subdirs)")
        .file(mustExist = false, canBeFile = false)
        .required()

    private val tenantPackage: String by option("--tenant_package", help = "Java package name for resolver bases (uses {tenantPackage}.resolverbases)")
        .required()

    private val verbose: Boolean by option("--verbose", help = "Print generation results")
        .flag()

    override fun run() {
        // Generate GRTs (enums, objects, inputs, interfaces, unions)
        val grtsCodegen = JavaGRTsCodegen()
        val grtsResult = grtsCodegen.generate(schemaFiles, grtOutputDir, grtPackage)

        // Generate Resolvers (separate step)
        val resolversCodegen = JavaResolversCodegen()
        val resolversResult = resolversCodegen.generate(schemaFiles, resolverGeneratedDir, grtPackage, tenantPackage)

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
            echo("  - ${resolversResult.resolverFileCount()} resolver file(s) containing ${resolversResult.resolverCount()} resolver(s)")
        }
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = JavaGRTsGenerator().main(args)
    }
}
