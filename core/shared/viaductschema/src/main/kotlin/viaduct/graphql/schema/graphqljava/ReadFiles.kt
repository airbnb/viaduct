package viaduct.graphql.schema.graphqljava

import graphql.parser.MultiSourceReader
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

fun readTypesFromFiles(inputFiles: List<File>): TypeDefinitionRegistry =
    readTypes(
        inputFiles,
        { file -> InputStreamReader(file.inputStream()) },
        { file -> file.path }
    )

private fun <T> readTypes(
    inputFiles: List<T>,
    toReader: (T) -> Reader,
    toPath: (T) -> String
): TypeDefinitionRegistry {
    val reader =
        MultiSourceReader
            .newMultiSourceReader()
            .apply {
                inputFiles.forEach {
                    this.reader(toReader(it), toPath(it))
                }
            }.trackData(true)
            .build()
    return SchemaParser().parse(reader)
}

fun readTypes(input: String): TypeDefinitionRegistry {
    val result = TypeDefinitionRegistry()
    val doc = Parser.parse(input)
    result.merge(SchemaParser().buildRegistry(doc))
    return result
}
