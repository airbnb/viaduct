package viaduct.engine.runtime2.schema

import graphql.GraphQLContext
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.language.Document
import graphql.language.OperationDefinition
import graphql.parser.Parser
import graphql.validation.Validator
import java.util.Locale
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.graphql.schema.ViaductSchema

fun ViaductAndGJSchema.selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> = selectionParser().selectionsFrom(fragment)

/** Parses and decodes one validated query or mutation operation with raw request variables. */
fun ViaductAndGJSchema.operationSelectionsFrom(
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
    locale: Locale = Locale.getDefault(),
): SelectionForest {
    val document = Parser.parse(documentSource)
    graphQLContext.put(graphql.normalized.NormalizedDocument.ENABLED, true)
    val validation = Validator().validateDocumentWithResult(graphQLSchema, document, { true }, locale, null, graphQLContext)
    val errors = validation.errors()
    require(errors.isEmpty()) {
        errors.joinToString(
            prefix = "Invalid GraphQL document: ",
            separator = "; ",
        ) { it.message }
    }
    val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
    val operation =
        if (operationName == null) {
            require(operations.size == 1) {
                "An operation name is required for a document containing multiple operations"
            }
            operations.single()
        } else {
            operations.singleOrNull { it.name == operationName }
                ?: throw IllegalArgumentException("Unknown operation: $operationName")
        }
    @Suppress("UNCHECKED_CAST")
    val rawVariables = RawVariables.of(variables as Map<String, Any>)
    val coercedVariables =
        ValuesResolver.coerceVariableValues(
            graphQLSchema,
            operation.variableDefinitions,
            rawVariables,
            graphQLContext,
            locale,
        )
    return NormalizedSelectionSource(
        requireNotNull(validation.normalizedDocument()),
        graphQLSchema,
        loweredSchema,
        coercedVariables,
        graphQLContext,
        locale
    ).selections(operation)
}

/** Unbound fragment variables require an explicit owner; bindings contain only ordinary input data. */
fun ViaductAndGJSchema.materializeSelectionsFrom(
    source: String,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
    variableTarget: ResolverTarget? = null,
    preserveSourceResponseKeys: Boolean = false,
): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> =
    NormalizedFragmentParser(
        sourceSchema = graphQLSchema,
        schema = loweredSchema,
        variableValues = bindings,
        variableTarget =
            variableTarget
                ?: variableField?.let(ResolverTarget::FieldValueResolverTarget),
        preserveSourceResponseKeys = preserveSourceResponseKeys,
    ).materializeSelectionsFrom(source)

internal fun ViaductAndGJSchema.fragmentFromDocument(
    document: Document,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
    variableTarget: ResolverTarget? = null,
): ParsedSelectionFragment =
    NormalizedFragmentParser(
        sourceSchema = graphQLSchema,
        schema = loweredSchema,
        variableValues = bindings,
        variableTarget = variableTarget ?: variableField?.let(ResolverTarget::FieldValueResolverTarget),
        preserveSourceResponseKeys = true,
    ).parseFragment(document)

private fun ViaductAndGJSchema.selectionParser(): NormalizedFragmentParser =
    NormalizedFragmentParser(
        sourceSchema = graphQLSchema,
        schema = loweredSchema,
        variableValues = emptyMap(),
    )
