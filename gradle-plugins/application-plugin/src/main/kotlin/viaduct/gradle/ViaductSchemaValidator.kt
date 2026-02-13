package viaduct.gradle

import graphql.GraphQLError
import graphql.GraphQLException
import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.schema.idl.errors.SchemaProblem
import graphql.validation.ValidationError
import java.io.File
import java.io.StringReader
import java.nio.file.Path
import org.slf4j.Logger
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.rules.DefaultSchemaValidator

class ViaductSchemaValidator(private val logger: Logger) {
    /**
     * Validates a schema using both GraphQL-Java syntax validation and Viaduct-specific rules.
     * If syntax validation fails, Viaduct validation is skipped.
     *
     * @param schemaFiles All schema files to validate (including framework-generated files)
     * @param excludeFromViaductValidation Files whose Viaduct validation errors should be filtered out.
     *        These files are still included in parsing and type resolution, but errors originating
     *        from them are suppressed. Typically used for framework-generated files like BUILTIN_SCHEMA.
     */
    fun validateSchema(
        schemaFiles: Collection<File>,
        excludeFromViaductValidation: Collection<File> = emptyList()
    ): List<GraphQLError> {
        logger.debug("Validating schema from: {}", schemaFiles.joinToString(",") { it.absolutePath })

        val syntaxErrors = performSyntaxValidation(schemaFiles)
        if (syntaxErrors.isNotEmpty()) {
            logger.warn("Schema syntax validation failed. Skipping Viaduct-specific validation.")
            return syntaxErrors
        }

        return performViaductValidation(schemaFiles, excludeFromViaductValidation)
    }

    private fun performSyntaxValidation(schemaFiles: Collection<File>): List<GraphQLError> {
        try {
            if (schemaFiles.isEmpty()) {
                throw IllegalArgumentException("Schema content is empty or blank")
            }

            var hasContent = false
            val reader = MultiSourceReader.newMultiSourceReader()
                .apply {
                    schemaFiles.forEach { file ->
                        logger.debug("Reading file {}", file.absolutePath)
                        val content = file.readText(Charsets.UTF_8)
                        if (content.isNotBlank()) {
                            hasContent = true
                        }
                        reader(StringReader(content), file.path)
                    }
                }
                .trackData(true)
                .build()
            if (!hasContent) {
                throw IllegalArgumentException("Schema content is empty or blank")
            }

            val typeRegistry = SchemaParser().parse(reader)
            UnExecutableSchemaGenerator.makeUnExecutableSchema(typeRegistry)

            logger.debug("Schema syntax validation successful. Found {} types defined.", typeRegistry.types().size)
            return emptyList()
        } catch (e: SchemaProblem) {
            return e.errors
        } catch (e: GraphQLException) {
            return listOf(ValidationError.newValidationError().description(e.message).build())
        } catch (e: Exception) {
            return listOf(ValidationError.newValidationError().description(e.message).build())
        }
    }

    private fun performViaductValidation(
        schemaFiles: Collection<File>,
        excludeFromViaductValidation: Collection<File> = emptyList()
    ): List<GraphQLError> {
        try {
            logger.debug("Running Viaduct-specific validation rules...")

            val schema = ViaductSchema.fromTypeDefinitionRegistry(schemaFiles.toList())
            val allErrors = DefaultSchemaValidator.validate(schema)

            // Filter out errors originating from excluded files (e.g., framework-generated BUILTIN_SCHEMA)
            val excludedPaths = excludeFromViaductValidation.mapNotNull { normalizePath(it.path) }.toSet()
            val errors = if (excludedPaths.isEmpty()) {
                allErrors
            } else {
                allErrors.filter { error ->
                    val sourceName = error.location.sourceLocation?.sourceName
                    val normalizedSourceName = sourceName?.let { normalizePath(it) }
                    normalizedSourceName == null || normalizedSourceName !in excludedPaths
                }
            }

            if (errors.isEmpty()) {
                logger.debug("Viaduct schema validation passed. Found {} types defined.", schema.types.size)
                return emptyList()
            }

            logger.error("Viaduct schema validation failed with {} error(s)", errors.size)
            errors.forEach { error ->
                logger.error("  [{}] {}: {}", error.code, error.location, error.message)
            }
            return errors.map { convertToGraphQLError(it) }
        } catch (e: SchemaProblem) {
            logger.error("Schema error during Viaduct validation: {}", e.message, e)
            return listOf(
                ValidationError.newValidationError()
                    .description("Viaduct validation failed: ${e.message}")
                    .build()
            )
        } catch (e: GraphQLException) {
            logger.error("GraphQL error during Viaduct validation: {}", e.message, e)
            return listOf(
                ValidationError.newValidationError()
                    .description("Viaduct validation failed: ${e.message}")
                    .build()
            )
        } catch (e: Exception) {
            logger.error("Unexpected error during Viaduct validation: {}", e.message, e)
            return listOf(
                ValidationError.newValidationError()
                    .description("Viaduct validation failed: ${e.message}")
                    .build()
            )
        }
    }

    private fun convertToGraphQLError(error: SchemaValidationError): GraphQLError {
        val description = buildString {
            append("[${error.code}] ")
            append("${error.location}: ")
            append(error.message)
        }
        return ValidationError.newValidationError()
            .description(description)
            .build()
    }

    private fun normalizePath(path: String): String? = runCatching { Path.of(path).toAbsolutePath().normalize().toString() }.getOrNull()
}
