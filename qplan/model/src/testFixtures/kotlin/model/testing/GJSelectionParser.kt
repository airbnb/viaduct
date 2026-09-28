package model.testing

import viaduct.graphql.schema.ViaductSchema

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ValuesResolver
import graphql.execution.conditional.ConditionalNodes
import graphql.language.BooleanValue
import graphql.language.DirectivesContainer
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.language.VariableReference
import graphql.introspection.Introspection
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLTypeUtil
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import model.Arguments
import model.EngineInputData
import model.InclusionCondition
import model.MaterializeSelectionForest
import model.SourceSchemaAdapter
import model.SelectionForest
import model.requireField
import model.requireQueryTypeDef
import model.requireType
import model.registry.ResolverTarget
import model.spec.SpecSelection
import model.spec.flatten
import model.spec.flattenForMaterialization
import viaduct.engine.api.FieldDirectives

/**
 * Parses and validates external GraphQL fragment text against the unaugmented source schema.
 *
 * Decoded selections are mapped directly to canonical definitions in [schema]. Node-valued source
 * fields retain their source coordinates.
 */
internal class GJSelectionParser(
    private val schema: GJSchema,
    private val variableValues: Map<String, EngineInputData?>,
    private val variableTarget: ResolverTarget? = null,
    private val preserveSourceResponseKeys: Boolean = false,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private var effectiveVariableTarget = variableTarget

    fun selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> {
        val parsed = specSelectionsFrom(fragment)
        val selections = flatten(schema, parsed.nominalType, parsed.selections)
        return parsed.nominalType to selections
    }

    fun selectionsFrom(
        operation: OperationDefinition,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale,
        fragmentsByName: Map<String, FragmentDefinition> = emptyMap(),
    ): SelectionForest {
        require(operation.operation == OperationDefinition.Operation.QUERY) {
            "Qplan operation decoding supports query operations only"
        }
        require(operation.directives.isEmpty()) {
            "Applied operation directives are unsupported"
        }
        val selections =
            decodeSelectionSet(
                selectionSet = operation.selectionSet,
                typeInScope = schema.graphQLSchema.queryType,
                argumentDecoder =
                    CoercedArgumentDecoder(
                        variables = variables,
                        graphQLContext = graphQLContext,
                        locale = locale,
                    ),
                mode = TranslationMode.EXTERNAL_OPERATION,
                fragmentsByName = fragmentsByName,
            )
        return flatten(schema, schema.requireQueryTypeDef(), selections)
    }

    fun materializeSelectionsFrom(
        fragment: String,
    ): Pair<ViaductSchema.CompositeTypeDef, MaterializeSelectionForest> {
        val parsed = specSelectionsFrom(fragment)
        val selections =
            flattenForMaterialization(schema, parsed.nominalType, parsed.selections)
        return parsed.nominalType to selections
    }

    fun specSelectionsFrom(fragment: String): ParsedSpecFragment {
        val document = Parser.parse(fragment)
        val definition =
            document.definitions.singleOrNull() as? FragmentDefinition
                ?: throw IllegalArgumentException("Expected exactly one named fragment definition")
        require(definition.directives.isEmpty()) {
            "Fragment definitions cannot be conditional"
        }
        validateFragment(document)

        val typeConditionName = definition.typeCondition.name!!
        val typeCondition = schema.requireType(typeConditionName) as ViaductSchema.CompositeTypeDef
        if (effectiveVariableTarget == null) {
            effectiveVariableTarget =
                ResolverTarget.FieldValueResolverTarget(
                    typeCondition.possibleObjectTypes
                        .first()
                        .fields
                        .first(),
                )
        }
        val graphQLTypeCondition =
            schema.graphQLSchema.getType(typeConditionName) as GraphQLCompositeType
        val specSelections =
            decodeSelectionSet(
                selectionSet = definition.selectionSet,
                typeInScope = graphQLTypeCondition,
                argumentDecoder = LiteralArgumentDecoder(),
                mode = TranslationMode.INTERNAL_FRAGMENT,
            )
        return ParsedSpecFragment(typeCondition, specSelections)
    }

    private fun validateFragment(document: Document) {
        val errors =
            Validator()
                .validateDocument(schema.graphQLSchema, document, Locale.ENGLISH)
                .filterNot { it.validationErrorType in STANDALONE_FRAGMENT_ERRORS }
        require(errors.isEmpty()) {
            errors.joinToString(
                prefix = "Invalid GraphQL fragment: ",
                separator = "; ",
            ) { it.message }
        }
    }

    private fun decodeSelectionSet(
        selectionSet: SelectionSet,
        typeInScope: GraphQLCompositeType,
        argumentDecoder: ArgumentDecoder,
        mode: TranslationMode,
        fragmentsByName: Map<String, FragmentDefinition> = emptyMap(),
    ): List<SpecSelection> =
        selectionSet.selections.flatMap { selection ->
            when (selection) {
                is Field ->
                    listOfNotNull(
                        decodeField(
                            selection,
                            typeInScope,
                            argumentDecoder,
                            mode,
                            fragmentsByName,
                        ),
                    )
                is InlineFragment ->
                    listOfNotNull(
                        decodeInlineFragment(
                            fragment = selection,
                            typeInScope = typeInScope,
                            argumentDecoder = argumentDecoder,
                            mode = mode,
                            fragmentsByName = fragmentsByName,
                        ),
                    )
                is FragmentSpread -> {
                    require(mode == TranslationMode.EXTERNAL_OPERATION) {
                        "Named fragment spreads must be inlined before constructing spec selections"
                    }
                    val fragment =
                        fragmentsByName[selection.name]
                            ?: throw IllegalArgumentException(
                                "Missing named fragment definition: ${selection.name}",
                            )
                    listOfNotNull(
                        decodeNamedFragment(
                            fragment = fragment,
                            inclusionCondition = argumentDecoder.decodeCondition(selection),
                            argumentDecoder = argumentDecoder,
                            fragmentsByName = fragmentsByName,
                        ),
                    )
                }
                else -> throw IllegalArgumentException("Unexpected GraphQL selection: $selection")
            }
        }

    private fun decodeField(
        field: Field,
        typeInScope: GraphQLCompositeType,
        argumentDecoder: ArgumentDecoder,
        mode: TranslationMode,
        fragmentsByName: Map<String, FragmentDefinition>,
    ): SpecSelection.Field? {
        if (mode == TranslationMode.EXTERNAL_OPERATION && field.name == "__typename") {
            return null
        }
        val fieldDefinition =
            Introspection.getFieldDef(
                schema.graphQLSchema,
                typeInScope,
                field.name,
            )!!
        val arguments = argumentDecoder.decode(field, fieldDefinition)
        val subselections =
            field.selectionSet?.let { selectionSet ->
                val resultType =
                    GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as GraphQLCompositeType
                decodeSelectionSet(
                    selectionSet,
                    resultType,
                    argumentDecoder,
                    mode,
                    fragmentsByName,
                )
            }
        val canonicalField = sourceSchema.field(typeInScope.name, field.name)
        return SpecSelection.Field.of(
            alias =
                field.alias ?: field.name.takeIf {
                    preserveSourceResponseKeys && canonicalField.name != field.name
                },
            field = canonicalField,
            arguments = arguments,
            subselections = subselections,
            inclusionCondition = argumentDecoder.decodeCondition(field),
            fieldDirectives = ParsedFieldDirectives(field.directives.map { it.name }.filterNot(EXECUTION_DIRECTIVES::contains)),
        )
    }

    private fun decodeInlineFragment(
        fragment: InlineFragment,
        typeInScope: GraphQLCompositeType,
        argumentDecoder: ArgumentDecoder,
        mode: TranslationMode,
        fragmentsByName: Map<String, FragmentDefinition>,
    ): SpecSelection.InlineFragment? {
        val typeConditionName = fragment.typeCondition?.name
        val graphQLTypeCondition =
            typeConditionName?.let {
                schema.graphQLSchema.getType(it) as GraphQLCompositeType
            }
        val modelTypeCondition =
            typeConditionName?.let { schema.requireType(it) as ViaductSchema.CompositeTypeDef }
        val selections =
            decodeSelectionSet(
                fragment.selectionSet,
                graphQLTypeCondition ?: typeInScope,
                argumentDecoder,
                mode,
                fragmentsByName,
            )
        if (selections.isEmpty()) return null
        return SpecSelection.InlineFragment.of(
            typeCondition = modelTypeCondition,
            selections = selections,
            inclusionCondition = argumentDecoder.decodeCondition(fragment),
        )
    }

    private fun decodeNamedFragment(
        fragment: FragmentDefinition,
        inclusionCondition: InclusionCondition,
        argumentDecoder: ArgumentDecoder,
        fragmentsByName: Map<String, FragmentDefinition>,
    ): SpecSelection.InlineFragment? {
        require(fragment.directives.isEmpty()) { "Fragment definitions cannot be conditional" }
        val typeConditionName = fragment.typeCondition.name!!
        val graphQLTypeCondition =
            schema.graphQLSchema.getType(typeConditionName) as GraphQLCompositeType
        val selections =
            decodeSelectionSet(
                fragment.selectionSet,
                graphQLTypeCondition,
                argumentDecoder,
                TranslationMode.EXTERNAL_OPERATION,
                fragmentsByName,
            )
        if (selections.isEmpty()) return null
        return SpecSelection.InlineFragment.of(
            typeCondition = schema.requireType(typeConditionName) as ViaductSchema.CompositeTypeDef,
            selections = selections,
            inclusionCondition = inclusionCondition,
        )
    }

    private sealed interface ArgumentDecoder {
        fun decode(
            field: Field,
            fieldDefinition: GraphQLFieldDefinition,
        ): Map<String, Any?>

        fun decodeCondition(container: DirectivesContainer<*>): InclusionCondition

    }

    private enum class TranslationMode {
        EXTERNAL_OPERATION,
        INTERNAL_FRAGMENT,
    }

    private inner class LiteralArgumentDecoder : ArgumentDecoder {
        override fun decode(
            field: Field,
            fieldDefinition: GraphQLFieldDefinition,
        ): Map<String, Any?> {
            val suppliedArguments = field.arguments.associateBy { it.name }
            return fieldDefinition.arguments
                .mapNotNull { argumentDefinition ->
                    val suppliedArgument = suppliedArguments[argumentDefinition.name]
                    when {
                        suppliedArgument != null ->
                            argumentDefinition.name to
                                decodeLiteral(
                                    type = argumentDefinition.type,
                                    value = suppliedArgument.value,
                                    variableValues = variableValues,
                                    schema = schema,
                                    variableTarget = effectiveVariableTarget,
                                )
                        argumentDefinition.hasSetDefaultValue() ->
                            argumentDefinition.name to
                                decodeInputValue(
                                    argumentDefinition.type,
                                    argumentDefinition.argumentDefaultValue,
                                    variableValues,
                                    schema,
                                    effectiveVariableTarget,
                                )
                        else -> null
                    }
                }.toMap()
        }

        override fun decodeCondition(container: DirectivesContainer<*>): InclusionCondition =
            container.directives.filter { it.name in CONDITIONAL_DIRECTIVES }.fold(
                InclusionCondition.Always as InclusionCondition,
            ) { accumulated, directive ->
                val value = directive.arguments.single { it.name == "if" }.value
                val required = directive.name == "include"
                val condition =
                    when (value) {
                        is BooleanValue ->
                            if (value.isValue == required) {
                                InclusionCondition.Always
                            } else {
                                InclusionCondition.Never
                            }
                        is VariableReference -> {
                            val bound = variableValues[value.name]
                            when {
                                variableValues.containsKey(value.name) ->
                                    if (
                                        requireNotNull(bound as? Boolean) {
                                            "Directive variable ${value.name} must be Boolean"
                                        } == required
                                    ) {
                                        InclusionCondition.Always
                                    } else {
                                        InclusionCondition.Never
                                    }
                                else ->
                                    InclusionCondition.requires(
                                        mapOf(
                                            Arguments.Variable.of(
                                                requireNotNull(effectiveVariableTarget),
                                                value.name,
                                            ) to required,
                                        ),
                                    )
                            }
                        }
                        else -> error("Directive @${directive.name}(if:) must be Boolean")
                    }
                accumulated.and(condition)
            }
    }

    private inner class CoercedArgumentDecoder(
        private val variables: CoercedVariables,
        private val graphQLContext: GraphQLContext,
        private val locale: Locale,
    ) : ArgumentDecoder {
        override fun decode(
            field: Field,
            fieldDefinition: GraphQLFieldDefinition,
        ): Map<String, Any?> {
            val values =
                ValuesResolver.getArgumentValues(
                    schema.graphQLSchema.codeRegistry,
                    fieldDefinition.arguments,
                    field.arguments,
                    variables,
                    graphQLContext,
                    locale,
                )
            return fieldDefinition.arguments
                .mapNotNull { argumentDefinition ->
                    if (argumentDefinition.name !in values) {
                        null
                    } else {
                        argumentDefinition.name to
                            decodeExternalInputValue(
                                type = argumentDefinition.type,
                                value = values[argumentDefinition.name],
                                schema = schema,
                            )
                    }
                }.toMap()
        }

        override fun decodeCondition(container: DirectivesContainer<*>): InclusionCondition {
            return if (
                ConditionalNodes().shouldInclude(
                    container,
                    variables.toMap(),
                    schema.graphQLSchema,
                    graphQLContext,
                )
            ) {
                InclusionCondition.Always
            } else {
                InclusionCondition.Never
            }
        }
    }

    private companion object {
        val CONDITIONAL_DIRECTIVES = setOf("skip", "include")
        val EXECUTION_DIRECTIVES = CONDITIONAL_DIRECTIVES + "defer"

        val STANDALONE_FRAGMENT_ERRORS =
            setOf(
                ValidationErrorType.UnusedFragment,
                ValidationErrorType.UndefinedVariable,
            )
    }
}

internal data class ParsedSpecFragment(
    val nominalType: ViaductSchema.CompositeTypeDef,
    val selections: List<SpecSelection>,
)

/** Generic no-argument field directives retained by the qplan test-fixture parser. */
private class ParsedFieldDirectives(
    directiveNames: List<String>,
) : FieldDirectives {
    private val names = directiveNames.toSet()

    override fun hasDirective(
        name: String,
        args: ((Map<String, Any?>) -> Boolean)?,
    ): Boolean = name in names && (args == null || args(emptyMap()))
}
