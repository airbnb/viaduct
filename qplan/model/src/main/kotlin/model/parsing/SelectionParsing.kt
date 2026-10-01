package model.parsing

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.parser.Parser
import graphql.schema.GraphQLSchema
import graphql.validation.Validator
import java.util.Locale
import model.Assumptions
import model.EngineInputData
import model.MaterializeSelectionForest
import model.SelectionForest
import model.lowering.ViaductAndGJSchema
import model.registry.ResolverTarget
import viaduct.graphql.schema.ViaductSchema

fun ViaductAndGJSchema.selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> = selectionParser().selectionsFrom(fragment)

/**
 * Decodes one post-validation query operation with already-coerced operation variables.
 *
 * Delivery-only `@defer` directives are transparent to qplan so deferred selections remain part of
 * full-operation demand. Named fragment spreads are inlined while decoding.
 */
fun Assumptions.selectionsFrom(
    sourceSchema: GraphQLSchema,
    operation: OperationDefinition,
    variables: CoercedVariables,
    graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
    locale: Locale = Locale.getDefault(),
    fragmentsByName: Map<String, FragmentDefinition> = emptyMap(),
): SelectionForest =
    GJSelectionParser(sourceSchema, schema, emptyMap())
        .selectionsFrom(operation, variables, graphQLContext, locale, fragmentsByName)

/** Parses and decodes one validated query operation with raw request variables. */
fun ViaductAndGJSchema.operationSelectionsFrom(
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
    locale: Locale = Locale.getDefault(),
): SelectionForest {
    val document = Parser.parse(documentSource)
    val errors = Validator().validateDocument(graphQLSchema, document, locale)
    require(errors.isEmpty()) {
        errors.joinToString(
            prefix = "Invalid GraphQL document: ",
            separator = "; ",
        ) { it.message }
    }
    val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
    val fragmentsByName =
        document
            .getDefinitionsOfType(FragmentDefinition::class.java)
            .associateBy { fragment -> fragment.name }
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
    return selectionParser().selectionsFrom(
        operation,
        coercedVariables,
        graphQLContext,
        locale,
        fragmentsByName,
    )
}

/** Unbound fragment variables require an explicit owner; bindings contain only ordinary input data. */
fun ViaductAndGJSchema.materializeSelectionsFrom(
    source: String,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
    variableTarget: ResolverTarget? = null,
    preserveSourceResponseKeys: Boolean = false,
): Pair<ViaductSchema.CompositeTypeDef, MaterializeSelectionForest> =
    GJSelectionParser(
        sourceSchema = graphQLSchema,
        schema = loweredSchema,
        variableValues = bindings,
        variableTarget =
            variableTarget
                ?: variableField?.let(ResolverTarget::FieldValueResolverTarget),
        preserveSourceResponseKeys = preserveSourceResponseKeys,
    ).materializeSelectionsFrom(source)

private fun ViaductAndGJSchema.selectionParser(): GJSelectionParser =
    GJSelectionParser(
        sourceSchema = graphQLSchema,
        schema = loweredSchema,
        variableValues = emptyMap(),
    )
