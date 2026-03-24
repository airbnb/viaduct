package viaduct.tenant.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import viaduct.graphql.utils.DefaultSchemaFactory

/**
 * CLI tool that generates the default Viaduct schema (scalars, directives, root types, etc.)
 * as SDL output. This is used during build time to create a schema file that can be
 * consumed by various build tools that require SDL input.
 */
class DefaultSchemaGenerator : CliktCommand() {
    private val outputFile: File by option("--output_file")
        .file(mustExist = false, canBeDir = false).required()

    private val includeNodeFields: Boolean by option("--include_node_fields").flag(default = false)

    private val includePageInfo: Boolean by option("--include_page_info").flag(default = false)

    private val includeRootTypes: Boolean by option("--include_root_types").flag("--no-include_root_types", default = true)

    override fun run() {
        val sdl = DefaultSchemaFactory.getDefaultSDL(
            includeNodeDefinition = DefaultSchemaFactory.IncludeNodeSchema.Always,
            includeNodeQueries = DefaultSchemaFactory.IncludeNodeSchema(includeNodeFields),
            includePageInfo = includePageInfo,
            includeRootTypes = includeRootTypes
        )
        // Write to output file
        outputFile.writeText(sdl)
        println("Generated default schema SDL to: ${outputFile.absolutePath}")
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = DefaultSchemaGenerator().main(args)
    }
}
