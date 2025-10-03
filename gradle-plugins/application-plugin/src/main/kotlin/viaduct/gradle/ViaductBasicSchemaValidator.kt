package viaduct.gradle

import graphql.GraphQLError
import graphql.GraphQLException
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.schema.idl.errors.SchemaProblem
import graphql.validation.ValidationError
import java.io.File
import java.io.Reader
import java.io.StringReader
import org.slf4j.Logger

class ViaductBasicSchemaValidator(private val logger: Logger) {
    fun validateSchema(schemaFiles: Collection<File>): List<GraphQLError> {
        logger.debug("Resolving from path: {}", schemaFiles.joinToString(",") { it.absolutePath })

        try {
            val schemaContent: String = readSchemaFromFiles(schemaFiles)
            validateSchemaContent(schemaContent)
            return emptyList()
        } catch (e: SchemaProblem) {
            return e.errors
        } catch (e: GraphQLException) {
            return listOf(ValidationError.newValidationError().description(e.message).build())
        } catch (e: Exception) {
            return listOf(ValidationError.newValidationError().description(e.message).build())
        }
    }

    fun readSchemaFromFiles(schemaFiles: Collection<File>): String =
        schemaFiles.joinToString(separator = "\n") {
            logger.debug("Reading file {}", it.absolutePath)
            it.readText()
        }

    private fun validateSchemaContent(schemaContent: Reader) {
        val schemaParser = SchemaParser()
        val typeRegistry = schemaParser.parse(schemaContent)

        UnExecutableSchemaGenerator.makeUnExecutableSchema(typeRegistry)

        logger.debug("Schema validation successful!")
        logger.debug("Found {} types defined.", typeRegistry.types().size)
    }

    private fun validateSchemaContent(schemaContent: String) {
        if (schemaContent.isBlank()) {
            throw IllegalArgumentException("Schema content is empty or blank")
        }
        validateSchemaContent(StringReader(schemaContent))
    }
}
