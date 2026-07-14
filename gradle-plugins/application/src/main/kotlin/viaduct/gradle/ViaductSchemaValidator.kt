package viaduct.gradle

import graphql.GraphQLError
import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.schema.idl.errors.SchemaProblem
import graphql.schema.validation.InvalidSchemaException
import graphql.validation.ValidationError
import java.io.File
import java.io.StringReader
import java.nio.file.Path
import org.slf4j.Logger
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.rules.DefaultSchemaValidator
import viaduct.graphql.schema.validation.rules.SchemaExtensionsValidator

class ViaductSchemaValidator(private val logger: Logger, private val extensionsOnly: Boolean = false) {
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
        excludeFromViaductValidation: Collection<File> = emptyList(),
        validScopes: Set<String>? = null
    ): List<GraphQLError> {
        logger.debug("Validating schema from: {}", schemaFiles.joinToString(",") { it.absolutePath })

        val syntaxErrors = performSyntaxValidation(schemaFiles)
        if (syntaxErrors.isNotEmpty()) {
            logger.warn("Schema syntax validation failed. Skipping Viaduct-specific validation.")
            return syntaxErrors
        }

        return performViaductValidation(schemaFiles, excludeFromViaductValidation, validScopes)
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
            e.errors.map { err ->
                val msg = err.message ?: ""
                if (isUnknownScopeDirective(msg)) {
                    ValidationError.newValidationError()
                        .description(
                            "$msg — The framework only emits the built-in `@scope` directive " +
                                "definition when the application opts into schema scoping. To fix, " +
                                "either (1) declare a scope universe in your application's build " +
                                "script so the framework provides the directive:\n" +
                                "    viaductApplication {\n" +
                                "        declareScoping { scopes(\"default\", \"...\") }\n" +
                                "    }\n" +
                                "or (2) define your own `directive @scope` in one of your schema " +
                                "files if you want to use the name outside the framework's scoping " +
                                "feature, or (3) remove the @scope usage from your schema."
                        )
                        .build()
                } else {
                    err
                }
            }
        } catch (e: InvalidSchemaException) {
            // InvalidSchemaException is @Internal and its errors field is package-private;
            // the combined message string is the only public API surface.
            val lines = (e.message ?: e.toString())
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "invalid schema:" }
            lines.map { line ->
                val enriched = if (EMPTY_TYPE_PATTERN in line) {
                    "$line Use a placeholder field (e.g., `_placeholder: Boolean`) as a workaround " +
                        "until a future GraphQL spec version permits empty output types."
                } else {
                    line
                }
                ValidationError.newValidationError().description(enriched).build()
            }.ifEmpty {
                listOf(ValidationError.newValidationError().description(e.message ?: e.toString()).build())
            }
        }
    }

    private fun performViaductValidation(
        schemaFiles: Collection<File>,
        excludeFromViaductValidation: Collection<File> = emptyList(),
        validScopes: Set<String>? = null
    ): List<GraphQLError> {
        logger.debug("Running Viaduct-specific validation rules...")

        val schema = ViaductSchema.fromTypeDefinitionRegistry(schemaFiles.toList())
        val allErrors = if (extensionsOnly) {
            SchemaExtensionsValidator.validate(schema)
        } else {
            DefaultSchemaValidator(strictMode = true).create(validScopes).validate(schema)
        }

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

    private fun isUnknownScopeDirective(msg: String): Boolean = UNKNOWN_SCOPE_DIRECTIVE_REGEX.containsMatchIn(msg)

    companion object {
        private const val EMPTY_TYPE_PATTERN = "must define one or more fields"

        // graphql-java's SchemaProblem message for an undefined directive comes in two flavors:
        //   - parser-level "Unknown directive 'scope'"
        //   - validator-level "tried to use an undeclared directive 'scope'"
        // Tolerate case on both prefixes (graphql-java has flipped casing in past releases), and
        // both `'scope'` and `'@scope'` renderings across graphql-java versions.
        private val UNKNOWN_SCOPE_DIRECTIVE_REGEX =
            Regex("(?i)(?:unknown directive|undeclared directive)\\s+'@?scope'")
    }
}
