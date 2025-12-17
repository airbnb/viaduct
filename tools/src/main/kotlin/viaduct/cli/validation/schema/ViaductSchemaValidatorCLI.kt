package viaduct.cli.validation.schema

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import graphql.GraphQLError
import graphql.GraphQLException
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.errors.SchemaProblem
import graphql.validation.ValidationError
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess
import viaduct.apiannotations.TestingApi

@TestingApi
class ViaductSchemaValidatorCLI : CliktCommand() {
    private val schemaPath: File by option("--schema").file(mustExist = true).required()

    override fun run() {
        val errors = validateSchema(schemaPath)
        if (errors.size > 0) {
            System.err.println(errors.joinToString("\n"))
            exitProcess(1)
        } else {
            println("Schema is valid")
        }
    }
}

@TestingApi
fun main(args: Array<String>) = ViaductSchemaValidatorCLI().main(args)

@TestingApi
fun validateSchema(schemaPath: File): List<GraphQLError> {
    println("Resolving from absolute path: ${schemaPath.absolutePath}")

    return try {
        println("Attempting to validate ${schemaPath.name}...")

        if (schemaPath.isDirectory) {
            println("Attempting to validate directory ${schemaPath.absolutePath}")
            val schemaContent = readSchemaFromDirectory(schemaPath)
            validateSchemaContent(schemaContent)
        } else {
            schemaPath.inputStream().use { stream ->
                validateSchemaContent(InputStreamReader(stream, StandardCharsets.UTF_8))
            }
        }

        emptyList()
    } catch (e: SchemaProblem) {
        return e.errors
    } catch (e: GraphQLException) {
        return listOf(createValidationError(e.message))
    } catch (e: Exception) {
        return listOf(createValidationError(e.message))
    }
}

fun readSchemaFromDirectory(schemaFile: File): String {
    return schemaFile.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.map {
        println("Reading file ${it.absolutePath}")
        it.readText()
    }.joinToString(separator = "\n")
}

private fun createValidationError(message: String? = "") =
    ValidationError.newValidationError()
        .description(message)
        .build()

private fun validateSchemaContent(schemaContent: Reader) {
    val schemaParser = SchemaParser()
    val typeRegistry = schemaParser.parse(schemaContent)

    val schemaGenerator = SchemaGenerator()
    val emptyWiring = RuntimeWiring.newRuntimeWiring().build()
    schemaGenerator.makeExecutableSchema(typeRegistry, emptyWiring)

    println("Schema validation successful!")
    println("Found ${typeRegistry.types().size} types defined.")
}

private fun validateSchemaContent(schemaContent: String) {
    if (schemaContent.isBlank()) {
        throw IllegalArgumentException("Schema content is empty or blank")
    }
    validateSchemaContent(StringReader(schemaContent))
}

// fun loadSchemasFromDirectory(dirPath: String): String? {
//     val directory = File(dirPath)
//     if (!directory.exists() || !directory.isDirectory) {
//         System.err.println("Error: Directory not found at '$dirPath'")
//         return null
//     }
//
//     return directory.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.map { it.readText() }.joinToString(separator = "\n")
// }

private fun extractArguments(args: Array<String>): Map<String, String> {
    val arguments: Map<String, String> = args.filter { it.startsWith("--") && it.contains("=") }.associate {
        val (key, value) = it.removePrefix("--").split("=", limit = 2)
        key to value
    }
    return arguments
}
