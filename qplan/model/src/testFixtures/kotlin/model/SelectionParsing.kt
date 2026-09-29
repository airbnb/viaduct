package model

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.parser.Parser
import graphql.validation.Validator
import java.util.Locale
import model.registry.ResolverTarget
import model.testing.GJSchema
import model.testing.GJSelectionParser
import viaduct.graphql.schema.ViaductSchema

/** Parses one post-validation fragment as test-fixture preparation outside semantic model logic. */
fun Assumptions.selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> = schema.selectionParser().selectionsFrom(fragment)

/**
 * Decodes one post-validation query operation with already-coerced operation variables.
 *
 * Delivery-only `@defer` directives are transparent to qplan so deferred selections remain part of
 * full-operation demand. Named fragment spreads are inlined while decoding.
 */
fun Assumptions.selectionsFrom(
    operation: OperationDefinition,
    variables: CoercedVariables,
    graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
    locale: Locale = Locale.getDefault(),
    fragmentsByName: Map<String, FragmentDefinition> = emptyMap(),
): SelectionForest =
    schema
        .selectionParser()
        .selectionsFrom(operation, variables, graphQLContext, locale, fragmentsByName)

/** Parses and decodes one validated query operation with raw request variables. */
fun Assumptions.operationSelectionsFrom(
    documentSource: String,
    variables: Map<String, Any?> = emptyMap(),
    operationName: String? = null,
    graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
    locale: Locale = Locale.getDefault(),
): SelectionForest {
    val graphQLSchema = (schema as GJSchema).graphQLSchema
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
    return selectionsFrom(
        operation,
        coercedVariables,
        graphQLContext,
        locale,
        fragmentsByName,
    )
}

/** Parses one post-validation GraphQL fragment into the model fragment used by tests. */
fun ViaductSchema.fragmentFrom(
    source: String,
    bindings: Map<String, EngineInputData?> = emptyMap(),
    variableField: ViaductSchema.ObjectField? = null,
    variableTarget: ResolverTarget? = null,
    preserveSourceResponseKeys: Boolean = false,
): Fragment =
    GJSelectionParser(
        schema = this as GJSchema,
        variableValues = bindings,
        variableTarget =
            variableTarget
                ?: variableField?.let(ResolverTarget::FieldValueResolverTarget),
        preserveSourceResponseKeys = preserveSourceResponseKeys,
    ).fragmentFrom(source)

/** Parses one post-validation GraphQL fragment without operation-variable bindings. */
fun Assumptions.fragmentFrom(source: String): Fragment = schema.fragmentFrom(source)

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun ViaductSchema.emptyFragmentOf(typeName: String): Fragment =
    Fragment.of(
        nominalType = requireType(typeName) as ViaductSchema.CompositeTypeDef,
        subselections = selectionForestOf(),
    )

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Assumptions.emptyFragmentOf(typeName: String): Fragment = schema.emptyFragmentOf(typeName)

private fun ViaductSchema.selectionParser(): GJSelectionParser =
    GJSelectionParser(
        schema = this as GJSchema,
        variableValues = emptyMap(),
    )

private fun GJSelectionParser.fragmentFrom(source: String): Fragment {
    val (nominalType, selections) = materializeSelectionsFrom(source)
    return Fragment.of(
        nominalType = nominalType,
        materializeSelections = selections,
    )
}
