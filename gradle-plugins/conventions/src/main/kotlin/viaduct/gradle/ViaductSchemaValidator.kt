package viaduct.gradle

import graphql.ErrorType
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.language.Node
import graphql.language.SourceLocation
import graphql.parser.InvalidSyntaxException
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.SchemaTraverser
import graphql.schema.idl.EchoingWiringFactory
import graphql.schema.idl.FastSchemaGenerator
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.schema.idl.errors.SchemaProblem
import graphql.schema.validation.InvalidSchemaException
import graphql.schema.validation.NoUnbrokenInputCycles
import graphql.schema.validation.SchemaValidationError as GraphqlSchemaValidationError
import graphql.schema.validation.SchemaValidationErrorCollector
import graphql.schema.validation.SchemaValidationErrorType
import graphql.schema.validation.SchemaValidator as GraphqlSchemaValidator
import graphql.schema.validation.TypeAndFieldRule
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.validation.ValidationError
import java.io.File
import java.util.concurrent.CancellationException
import org.slf4j.Logger
import viaduct.apiannotations.InternalApi
import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.SchemaDefinitionReconciler
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.graphqljava.readTypesFromFiles
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.rules.DefaultSchemaValidator
import viaduct.graphql.schema.validation.rules.SchemaExtensionsValidator

@InternalApi
class ViaductSchemaValidator(
    private val logger: Logger,
    private val extensionsOnly: Boolean = false,
    private val validateScopeConsistency: Boolean = false,
    private val sourceRoot: File? = null,
    private val validationClasspath: Collection<File> = emptyList(),
    private val finalContributors: List<String> = emptyList(),
    private val reportStructural: (GraphQLError) -> Unit = {},
    private val reportDiagnostic: (String, String?, SchemaValidationError) -> Unit = { _, _, _ -> },
) {
    // Match the loaded GraphQL Java's list-cycle policy when locating its diagnostics.
    private val inputCycleListsAreRejected by lazy {
        try {
            val registry = SchemaParser().parse("type Query { data(arg: Options): String } input Options { next: [Options!]! }")
            UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            false
        } catch (_: InvalidSchemaException) {
            true
        }
    }

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
            syntaxErrors.forEach(reportStructural)
            return syntaxErrors
        }

        return performViaductValidation(schemaFiles, excludeFromViaductValidation)
    }

    private fun performSyntaxValidation(schemaFiles: Collection<File>): List<GraphQLError> {
        if (schemaFiles.isEmpty()) {
            return listOf(schemaError("Schema content is empty or blank", emptyList()))
        }

        val sources = schemaFiles.map { file -> sourceName(file) to file.readText(Charsets.UTF_8) }
        if (sources.all { it.second.isBlank() }) {
            return listOf(schemaError("Schema content is empty or blank", emptyList()))
        }

        return try {
            val typeRegistry = SchemaDefinitionReconciler.parseSources(sources.asSequence())
            try {
                UnExecutableSchemaGenerator.makeUnExecutableSchema(typeRegistry)
            } catch (e: InvalidSchemaException) {
                return recoverSchemaErrors(e) { schemaValidationErrors(typeRegistry) }
            }
            logger.debug("Schema syntax validation successful. Found {} types defined.", typeRegistry.types().size)
            emptyList()
        } catch (e: SchemaProblem) {
            e.errors.map { error -> schemaError(error.message, error.locations.orEmpty()) }
        } catch (e: IllegalArgumentException) {
            listOf(schemaError(e.message ?: e.toString(), listOfNotNull((e.cause as? InvalidSyntaxException)?.location)))
        }
    }

    internal fun recoverSchemaErrors(
        original: InvalidSchemaException,
        attributeErrors: () -> List<GraphQLError>
    ): List<GraphQLError> {
        val attributed = try {
            attributeErrors()
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            logger.debug("Could not attribute schema validation errors; retaining original diagnostics", e)
            emptyList()
        } catch (e: LinkageError) {
            logger.debug("Could not link schema diagnostic attribution; retaining original diagnostics", e)
            emptyList()
        }
        val originalMessage = original.message ?: original.toString()
        if (attributed.isEmpty()) return listOf(schemaError(originalMessage, emptyList()))
        val descriptions = originalMessage.removePrefix("invalid schema:").lineSequence().filter(String::isNotBlank)
        return if (descriptions.all { description -> attributed.any { it.message.contains(description) } }) {
            attributed
        } else {
            attributed + schemaError(originalMessage, emptyList())
        }
    }

    // GraphQL Java's internal visitors are best-effort; recovery retains unmatched original diagnostics.
    private fun schemaValidationErrors(registry: TypeDefinitionRegistry): List<GraphQLError> {
        val wiring = EchoingWiringFactory.newEchoingWiring { builder ->
            registry.scalars().keys.filterNot { ScalarInfo.isGraphqlSpecifiedScalar(it) }.forEach {
                builder.scalar(EchoingWiringFactory.fakeScalar(it))
            }
        }
        val schema = FastSchemaGenerator().makeExecutableSchema(SchemaGenerator.Options.defaultOptions().withValidation(false), registry, wiring)
        val errors = linkedMapOf<GraphqlSchemaValidationError, GraphQLError>()
        var locations: List<SourceLocation> = emptyList()
        val collector = object : SchemaValidationErrorCollector() {
            override fun addError(error: GraphqlSchemaValidationError) {
                super.addError(error)
                val internal = locations.any { SchemaProvenance.parse(it.sourceName)?.supplyingPlugin != null }
                val description = if (!internal && error.classification in setOf(
                        SchemaValidationErrorType.ImplementingTypeLackOfFieldError,
                        SchemaValidationErrorType.InputObjectTypeLackOfFieldError,
                    )
                ) {
                    error.description + " Use a placeholder field (e.g., `_placeholder: Boolean`) as a workaround until a future GraphQL spec version permits empty output types."
                } else {
                    error.description
                }
                errors.putIfAbsent(error, schemaError(description, locations))
            }
        }
        val rules = GraphqlSchemaValidator().rules
        val visitor = object : GraphQLTypeVisitorStub() {
            override fun visitGraphQLType(
                node: GraphQLSchemaElement,
                context: TraverserContext<GraphQLSchemaElement>
            ): TraversalControl {
                rules.forEach { rule ->
                    if (rule is TypeAndFieldRule) {
                        typeAndFieldClaims(node).forEach { (claim, source) ->
                            locations = listOfNotNull(source?.sourceLocation)
                            claim.accept(context, rule)
                        }
                    } else {
                        locations = if (rule is NoUnbrokenInputCycles && node is GraphQLFieldDefinition) {
                            inputCycleLocations(node)
                        } else {
                            listOfNotNull((node as? GraphQLNamedSchemaElement)?.definition?.sourceLocation)
                        }
                        node.accept(context, rule)
                    }
                }
                return TraversalControl.CONTINUE
            }
        }
        SchemaTraverser().depthFirstFullSchema(
            listOf(visitor),
            schema,
            mapOf(GraphQLSchema::class.java to schema, SchemaValidationErrorCollector::class.java to collector),
        )
        return errors.values.toList()
    }

    // TypeAndFieldRule reports member failures while visiting the merged type.
    private fun typeAndFieldClaims(node: GraphQLSchemaElement): List<Pair<GraphQLSchemaElement, Node<*>?>> =
        when (node) {
            is GraphQLObjectType -> node.fieldDefinitions.map { field -> node.transform { it.clearFields().field(field) } to field.definition }
            is GraphQLInterfaceType -> node.fieldDefinitions.map { field -> node.transform { it.clearFields().field(field) } to field.definition }
            is GraphQLInputObjectType -> node.fields.map { field -> node.transform { it.clearFields().field(field) } to field.definition }
            is GraphQLEnumType -> node.values.map { value -> node.transform { it.clearValues().value(value) } to value.definition }
            else -> emptyList()
        }.ifEmpty { listOf(node to (node as? GraphQLNamedSchemaElement)?.definition) }

    // Cycle errors are emitted at the executable field, away from the input declarations.
    private fun inputCycleLocations(field: GraphQLFieldDefinition): List<SourceLocation> {
        val edges = mutableMapOf<GraphQLInputObjectType, List<Pair<GraphQLInputObjectField, GraphQLInputObjectType>>>()

        fun collect(type: GraphQLInputObjectType) {
            if (type in edges) return
            val fields = type.fields.mapNotNull { inputField ->
                val wrapped = (inputField.type as? GraphQLNonNull)?.wrappedType ?: return@mapNotNull null
                if (wrapped is GraphQLList && (!inputCycleListsAreRejected || wrapped.wrappedType !is GraphQLNonNull)) return@mapNotNull null
                val next = GraphQLTypeUtil.unwrapAll(wrapped) as? GraphQLInputObjectType ?: return@mapNotNull null
                inputField to next
            }
            edges[type] = fields
            fields.forEach { collect(it.second) }
        }
        field.arguments.forEach { arg -> (arg.type as? GraphQLInputObjectType)?.let(::collect) }

        fun reaches(
            from: GraphQLInputObjectType,
            target: GraphQLInputObjectType,
            visited: MutableSet<GraphQLInputObjectType>
        ): Boolean = from == target || (visited.add(from) && edges[from].orEmpty().any { reaches(it.second, target, visited) })
        return edges.flatMap { (type, fields) ->
            fields.filter { reaches(it.second, type, mutableSetOf()) }.mapNotNull { it.first.definition?.sourceLocation }
        }.distinct()
    }

    private fun schemaError(
        message: String,
        locations: List<SourceLocation>
    ): GraphQLError {
        val plugins = locations.mapNotNull { SchemaProvenance.parse(it.sourceName)?.supplyingPlugin }.distinct()
        val description = buildString {
            if (finalContributors.isNotEmpty()) {
                append(finalSchemaMessage(message))
            } else {
                append(if (plugins.isEmpty()) message else SchemaProvenance.pluginError(plugins, message))
            }
            locations.distinct().forEach { append(" ${it.sourceName}:${it.line}:${it.column}") }
        }
        val error: GraphqlErrorBuilder<*> = GraphqlErrorBuilder.newError()
        error.message(description)
        error.locations(locations)
        error.errorType(ErrorType.ValidationError)
        return error.build()
    }

    private fun performViaductValidation(
        schemaFiles: Collection<File>,
        excludeFromViaductValidation: Collection<File> = emptyList()
    ): List<GraphQLError> {
        logger.debug("Running Viaduct-specific validation rules...")

        val registry = sourceRoot?.let { readTypesFromFiles(schemaFiles.toList(), it) } ?: readTypesFromFiles(schemaFiles.toList())
        val schema = ViaductSchema.fromTypeDefinitionRegistry(registry)
        val rawDiagnostics = if (extensionsOnly) {
            SchemaExtensionsValidator.validate(schema).map { SchemaRuleDiagnostic(it, IntegratedSchemaValidation.BUILT_IN_PROVIDER, null) }
        } else {
            IntegratedSchemaValidation.validate(
                schema,
                DefaultSchemaValidator(strictMode = true, validateScopeConsistency = validateScopeConsistency).rules,
                validationClasspath,
            )
        }
        val diagnostics = rawDiagnostics.map { diagnostic ->
            diagnostic.copy(error = diagnostic.error.copy(message = finalSchemaMessage(diagnostic.error.message)))
        }
        diagnostics.forEach { diagnostic ->
            reportDiagnostic(diagnostic.provider, diagnostic.ruleId, diagnostic.error)
            if (diagnostic.error.severity == SchemaValidationError.Severity.WARNING) {
                logger.warn("[{}:{}] {}", diagnostic.provider, diagnostic.ruleId, diagnostic.error.message)
            }
        }
        val failures = diagnostics.filter { it.error.severity == SchemaValidationError.Severity.ERROR }.map { diagnostic ->
            diagnostic to if (diagnostic.provider == IntegratedSchemaValidation.BUILT_IN_PROVIDER) {
                diagnostic.error
            } else {
                diagnostic.error.copy(message = "[provider=${diagnostic.provider}, rule=${diagnostic.ruleId}] ${diagnostic.error.message}")
            }
        }
        val allErrors = failures.map { it.second }
        val pluginRuleErrors = failures.filter { it.first.provider != IntegratedSchemaValidation.BUILT_IN_PROVIDER }.map { it.second }.toSet()

        if (allErrors.isEmpty()) {
            logger.debug("Viaduct schema validation passed. Found {} types defined.", schema.types.size)
            return emptyList()
        }

        if (finalContributors.isNotEmpty()) {
            return allErrors.map { convertToGraphQLError(it) }
        }

        val excludedPaths = excludeFromViaductValidation.map { it.toPath().toAbsolutePath().normalize() }.toSet()

        if (excludedPaths.isEmpty() && allErrors.none { SchemaProvenance.parse(it.location.sourceLocation?.sourceName)?.supplyingPlugin != null }) {
            logTenantErrors(allErrors)
            return allErrors.map { convertToGraphQLError(it) }
        }

        // Partition errors into framework errors (from excluded files) and tenant errors
        val (frameworkErrors, tenantErrors) = allErrors.partition { error ->
            val sourceName = error.location.sourceLocation?.sourceName
            val sourcePath = sourceName?.let { (sourceRoot?.resolve(it) ?: File(it)).toPath().toAbsolutePath().normalize() }
            sourcePath in excludedPaths || SchemaProvenance.parse(sourceName)?.supplyingPlugin != null
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
            return frameworkErrors.map { convertToFrameworkGraphQLError(it) } +
                tenantErrors.filter { it in pluginRuleErrors }.map { convertToGraphQLError(it) }
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

    fun finalSchemaMessage(message: String): String =
        if (finalContributors.isEmpty()) {
            message
        } else {
            "Final-schema contribution error from '${finalContributors.joinToString("', '")}'; central locations are context: $message"
        }

    private fun sourceName(file: File): String = sourceRoot?.let { file.relativeTo(it).invariantSeparatorsPath } ?: file.invariantSeparatorsPath
}
