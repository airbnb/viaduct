package viaduct.engine.runtime2.schema

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.introspection.Introspection
import graphql.language.BooleanValue
import graphql.language.DirectivesContainer
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.VariableReference
import graphql.normalized.NormalizedDocument
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import graphql.validation.OperationValidationRule
import graphql.validation.Validator
import java.util.Locale
import viaduct.engine.api.FieldDirectives
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineInputData
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.lowering.loweredFieldFromSourceCoordinate
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.SelectionsParserUtils

/** Validates fragment templates once and interprets their ND through the shared selection API. */
internal class NormalizedFragmentParser(
    private val sourceSchema: GraphQLSchema,
    private val schema: ViaductSchema,
    private val variableValues: Map<String, EngineInputData?>,
    private val variableTarget: ResolverTarget? = null,
    private val preserveSourceResponseKeys: Boolean = false,
) {
    fun selectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> = materializeSelectionsFrom(fragment)

    fun materializeSelectionsFrom(fragment: String): Pair<ViaductSchema.CompositeTypeDef, SelectionForest> {
        val parsed = parseFragment(fragment)
        return parsed.nominalType to parsed.fragment.subselections
    }

    fun parseFragment(fragment: String): ParsedSelectionFragment {
        val document = Parser.parse(fragment)
        require(document.definitions.singleOrNull() is FragmentDefinition) { "Expected exactly one named fragment definition" }
        return parseFragment(document)
    }

    fun parseFragment(document: Document): ParsedSelectionFragment {
        val fragments = document.getDefinitionsOfType(FragmentDefinition::class.java)
        require(fragments.size == document.definitions.size) { "A resolver selection document may contain only fragment definitions" }
        require(fragments.map { it.name }.toSet().size == fragments.size) { "A resolver selection document may not contain duplicate fragment definitions" }
        val context = GraphQLContext.newContext().of(NormalizedDocument.ENABLED, true).build()
        val validation = Validator().validateDocumentWithResult(sourceSchema, document, { rule ->
            rule != OperationValidationRule.NO_UNUSED_FRAGMENTS && rule != OperationValidationRule.NO_UNDEFINED_VARIABLES
        }, Locale.ENGLISH, null, context)
        require(validation.errors().isEmpty()) { validation.errors().joinToString(prefix = "Invalid GraphQL fragment: ", separator = "; ") { it.message } }
        val definition = SelectionsParserUtils.findEntryPointFragment(fragments)
        val normalized = requireNotNull(validation.normalizedDocument())
        val (withRoot, rootId) = normalized.withFragmentRoot(definition)
        val type = schema.requireType(definition.typeCondition.name) as ViaductSchema.CompositeTypeDef
        val decoder = LiteralArgumentDecoder()
        val source = NormalizedSelectionSource(
            withRoot,
            sourceSchema,
            schema,
            CoercedVariables.emptyVariables(),
            context,
            Locale.ENGLISH,
            keyDecoder = { field ->
                val parent = sourceSchema.getType(field.parentTypeName) as GraphQLCompositeType
                val fieldDefinition = requireNotNull(Introspection.getFieldDef(sourceSchema, parent, field.source.name))
                val canonical = schema.loweredFieldFromSourceCoordinate(field.parentTypeName, field.source.name)
                ObjectEngineResult.Key.of(canonical, Arguments.of(canonical, decoder.decode(field.source, fieldDefinition)))
            },
            conditionDecoder = decoder::decodeCondition,
            eraseIntrospection = false,
            preserveSourceResponseKeys = preserveSourceResponseKeys
        )
        // Template errors belong to registry construction, even for unobserved branches.
        // Decode each stored source field once, without expanding fragment occurrences.
        withRoot.fields.values.forEach(source::key)
        withRoot.selectionSets.forEach { set -> set.selections.forEach { decoder.decodeCondition(it.source) } }
        return ParsedSelectionFragment(type, withRoot, rootId, source)
    }

    private inner class LiteralArgumentDecoder {
        fun decode(
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
                                    variableTarget = variableTarget,
                                )
                        argumentDefinition.hasSetDefaultValue() ->
                            argumentDefinition.name to
                                decodeInputValue(
                                    argumentDefinition.type,
                                    argumentDefinition.argumentDefaultValue,
                                    variableValues,
                                    schema,
                                    variableTarget,
                                )
                        else -> null
                    }
                }.toMap()
        }

        fun decodeCondition(container: DirectivesContainer<*>): InclusionCondition =
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
                                                requireNotNull(variableTarget) {
                                                    "Unbound fragment variable \$${value.name} requires an explicit resolver target"
                                                },
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

    private companion object {
        val CONDITIONAL_DIRECTIVES = setOf("skip", "include")
    }
}

/** A validated source graph and its fragment root; no second expanded representation is retained. */
internal class ParsedSelectionFragment(
    val nominalType: ViaductSchema.CompositeTypeDef,
    val document: NormalizedDocument,
    val rootId: Int,
    val source: NormalizedSelectionSource,
) {
    val fragment: Fragment = Fragment.of(nominalType, source.forest(rootId, nominalType.possibleObjectTypes))
}

/** Generic no-argument field directives retained by the parser. */
internal class ParsedFieldDirectives(
    directiveNames: List<String>,
) : FieldDirectives {
    private val names = directiveNames.toSet()

    override fun hasDirective(
        name: String,
        args: ((Map<String, Any?>) -> Boolean)?,
    ): Boolean = name in names && (args == null || args(emptyMap()))
}
