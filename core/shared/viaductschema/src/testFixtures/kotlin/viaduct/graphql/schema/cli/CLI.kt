package viaduct.graphql.schema.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Main entry point for the ViaductSchema CLI tools.
 *
 * Run with:
 *   ./gradlew :shared:viaductschema:runSchemaCli --args="<command> [options]"
 *
 * Available commands:
 *   schema2csv - Export schema types and fields to CSV files
 */
fun main(args: Array<String>) =
    CLI()
        .subcommands(
            Schema2CsvCommand(),
        )
        .main(args)

private class CLI : NoOpCliktCommand(
    name = "viaduct-schema",
    help = "ViaductSchema CLI tools for analyzing and exporting GraphQL schemas."
)
