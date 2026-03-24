package viaduct.graphql.schema.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory

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

private class CLI : CliktCommand(
    name = "viaduct-schema",
    help = "ViaductSchema CLI tools for analyzing and exporting GraphQL schemas."
) {
    private val debug: Boolean by option("--debug", "-d", help = "Enable debug logging")
        .flag(default = false)

    override fun run() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        if (debug) {
            root.level = Level.DEBUG
        } else {
            root.level = Level.INFO
        }
    }
}
