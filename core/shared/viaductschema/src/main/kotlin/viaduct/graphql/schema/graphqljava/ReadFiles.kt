package viaduct.graphql.schema.graphqljava

import graphql.parser.Parser
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.net.URL

/** Reads all the files in `inputFiles` and parses all of them into a
 * TypeDefinitionRegistry.  Intended to be used on a set of files that
 * is known to be parse-able - will fail with a random exception upon
 * first encountering a parsing error. */
fun readTypesFromURLs(inputFiles: List<URL>): TypeDefinitionRegistry =
    readTypes(
        inputFiles,
        { url -> url.openStream().reader(Charsets.UTF_8) },
        { url -> url.path }
    )

/** Reads all the files in `inputFiles` and parses all of them into a
 * TypeDefinitionRegistry.  Like [readTypesFromURLs], it is meant for a set of
 * files known to be parse-able - it will fail with a random exception upon
 * first encountering a parsing error.  Source names on the result are
 * '/'-separated on every platform, as [readTypesFromURLs] source names already
 * are; source names from other producers carry no such guarantee. */
fun readTypesFromFiles(inputFiles: List<File>): TypeDefinitionRegistry =
    readTypes(
        inputFiles,
        { file -> InputStreamReader(file.inputStream(), Charsets.UTF_8) },
        { file -> file.invariantSourceName() }
    )

fun readTypesFromFiles(
    inputFiles: List<File>,
    sourceRoot: File
): TypeDefinitionRegistry = readTypes(inputFiles, { it.reader(Charsets.UTF_8) }, { it.relativeTo(sourceRoot).invariantSeparatorsPath })

/**
 * This file's path with the platform separator rewritten to '/', for use as a parse-time source name.
 *
 * Only the platform separator is rewritten: where '/' is already the separator, a backslash is a
 * legal filename character.
 *
 * @param separator overridable so tests can exercise Windows-shaped paths on any platform.
 */
internal fun File.invariantSourceName(separator: Char = File.separatorChar): String = path.replace(separator, '/')

private fun <T> readTypes(
    inputFiles: List<T>,
    toReader: (T) -> Reader,
    toPath: (T) -> String
): TypeDefinitionRegistry {
    return SchemaDefinitionReconciler.parseSources(
        inputFiles.asSequence().map { input ->
            toPath(input) to toReader(input).use { it.readText() }
        }
    )
}

fun readTypes(input: String): TypeDefinitionRegistry {
    val result = TypeDefinitionRegistry()
    val doc = Parser.parse(input)
    result.merge(SchemaParser().buildRegistry(doc))
    return result
}
