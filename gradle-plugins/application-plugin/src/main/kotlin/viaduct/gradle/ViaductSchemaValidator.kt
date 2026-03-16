package viaduct.gradle

import graphql.GraphQLError
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
     * @param excludeFromViaductValidation Framework-generated files (e.g., BUILTIN_SCHEMA).
     *        These files are still included in parsing and type resolution, but errors originating
     *        from them are treated as internal framework errors. If any framework errors are found,
     *        they are reported with clear messaging and tenant errors are not returned.
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
        if (schemaFiles.isEmpty()) {
            return listOf(ValidationError.newValidationError().description("Schema content is empty or blank").build())
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
            return listOf(ValidationError.newValidationError().description("Schema content is empty or blank").build())
        }

        return try {
            val typeRegistry = SchemaParser().parse(reader)
            UnExecutableSchemaGenerator.makeUnExecutableSchema(typeRegistry)
            logger.debug("Schema syntax validation successful. Found {} types defined.", typeRegistry.types().size)
            emptyList()
        } catch (e: SchemaProblem) {
            e.errors
        }
    }

    private fun performViaductValidation(
        schemaFiles: Collection<File>,
        excludeFromViaductValidation: Collection<File> = emptyList()
    ): List<GraphQLError> {
        logger.debug("Running Viaduct-specific validation rules...")

        val schema = ViaductSchema.fromTypeDefinitionRegistry(schemaFiles.toList())
        val allErrors = DefaultSchemaValidator.validate(schema)

        if (allErrors.isEmpty()) {
            logger.debug("Viaduct schema validation passed. Found {} types defined.", schema.types.size)
            return emptyList()
        }

        val excludedPaths = excludeFromViaductValidation.mapNotNull { normalizePath(it.path) }.toSet()

        if (excludedPaths.isEmpty()) {
            logTenantErrors(allErrors)
            return allErrors.map { convertToGraphQLError(it) }
        }

        // Partition errors into framework errors (from excluded files) and tenant errors
        val (frameworkErrors, tenantErrors) = allErrors.partition { error ->
            val sourceName = error.location.sourceLocation?.sourceName
            val normalizedSourceName = sourceName?.let { normalizePath(it) }
            normalizedSourceName != null && normalizedSourceName in excludedPaths
        }

        // Framework errors indicate an internal problem — report and halt without reporting tenant errors
        if (frameworkErrors.isNotEmpty()) {
            logger.error(
                "Viaduct framework schema has {} validation error(s). " +
                    "This is an internal framework issue, not a problem with your schema.",
                frameworkErrors.size
            )
            frameworkErrors.forEach { error ->
                logger.error("  [{}] {}: {}", error.code, error.location, error.message)
            }
            return frameworkErrors.map { convertToFrameworkGraphQLError(it) }
        }

        if (tenantErrors.isNotEmpty()) {
            logTenantErrors(tenantErrors)
            return tenantErrors.map { convertToGraphQLError(it) }
        }

        return emptyList()
    }

    private fun logTenantErrors(errors: List<SchemaValidationError>) {
        logger.error("Viaduct schema validation failed with {} error(s)", errors.size)
        errors.forEach { error ->
            logger.error("  [{}] {}: {}", error.code, error.location, error.message)
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

    private fun convertToFrameworkGraphQLError(error: SchemaValidationError): GraphQLError {
        val description = buildString {
            append("Internal framework error: ")
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
