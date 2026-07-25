package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import graphql.language.Field as GJField
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.util.FpKit
import java.util.Locale
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.collectVariableReferences

/**
 * Replaces variable references in the arguments and directives directly attached to a field with
 * typed value literals.
 *
 * This transformation is intentionally shallow: it does not traverse the field's selection set.
 * Materialized paths are rebuilt from the terminal selection toward the root, and each ancestor
 * field is inlined with the variable frame that originally collected it. Descendant selections may
 * belong to a different plan and variable frame, so recursively inlining them would bind variables
 * using the wrong execution parameters.
 *
 * Arguments are resolved as complete values before being converted back to literals. This preserves
 * GraphQL coercion behavior for defaults, absent variables, nulls, nested inputs, and supported
 * scalars.
 */
internal class VariableInliner(
    private val schema: ViaductSchema,
    private val variables: CoercedVariables,
    private val ctx: GraphQLContext,
    private val locale: Locale,
    private val fieldArgumentDefinitions: List<GraphQLArgument>,
) {
    constructor(parameters: ExecutionParameters) : this(
        schema = parameters.engineExecutionContext.activeSchema,
        variables = parameters.coercedVariables,
        ctx = parameters.executionContext.graphQLContext,
        locale = parameters.executionContext.locale,
        fieldArgumentDefinitions = parameters.executionStepInfo.fieldDefinition.arguments,
    )

    fun shallowInline(field: QueryPlan.CollectedField): QueryPlan.CollectedField {
        var changed = false
        val fields = field.mergedField.fields.map { astField ->
            shallowInline(astField).also {
                changed = changed || it !== astField
            }
        }
        if (!changed) return field

        return field.copy(
            mergedField = MergedField.newMergedField(fields)
                .addDeferredExecutions(field.mergedField.deferredExecutions)
                .build()
        )
    }

    fun shallowInline(field: GJField): GJField {
        val hasVariableReferences = field.arguments.any { it.collectVariableReferences().isNotEmpty() } ||
            field.directives.any { it.collectVariableReferences().isNotEmpty() }
        if (!hasVariableReferences) return field

        return field.transform { builder ->
            builder
                .arguments(shallowInline(field.arguments, fieldArgumentDefinitions))
                .directives(field.directives.map(::shallowInline))
        }
    }

    private fun shallowInline(directive: Directive): Directive {
        if (directive.collectVariableReferences().isEmpty()) return directive

        val definition = requireNotNull(schema.schema.getDirective(directive.name)) {
            "Directive @${directive.name} is not defined"
        }
        return directive.transform { builder ->
            builder.arguments(shallowInline(directive.arguments, definition.arguments))
        }
    }

    private fun shallowInline(
        arguments: List<Argument>,
        definitions: List<GraphQLArgument>,
    ): List<Argument> {
        val variableArguments = arguments.filter { it.collectVariableReferences().isNotEmpty() }
        if (variableArguments.isEmpty()) return arguments

        val values = ValuesResolver.getArgumentValues(
            schema.schema.codeRegistry,
            definitions,
            arguments,
            variables,
            ctx,
            locale,
        )
        val definitionsByName = definitions.associateBy(GraphQLArgument::getName)
        val variableArgumentNames = variableArguments.mapTo(mutableSetOf(), Argument::getName)

        return arguments.mapNotNull { argument ->
            if (argument.name !in variableArgumentNames) return@mapNotNull argument
            if (!values.containsKey(argument.name)) return@mapNotNull null

            val definition = requireNotNull(definitionsByName[argument.name]) {
                "Argument ${argument.name} is not defined"
            }
            val literal = literalFor(values[argument.name], definition.type)
            argument.transform { builder -> builder.value(literal) }
        }
    }

    private fun literalFor(
        value: Any?,
        type: GraphQLInputType,
    ): Value<*> =
        when {
            value == null -> NullValue.newNullValue().build()
            type is GraphQLNonNull -> literalFor(value, type.wrappedType as GraphQLInputType)
            type is GraphQLList -> ArrayValue.newArrayValue()
                .values(
                    FpKit.toListOrSingletonList<Any?>(value).map {
                        literalFor(it, type.wrappedType as GraphQLInputType)
                    }
                )
                .build()
            type is GraphQLInputObjectType -> {
                val values = value as? Map<*, *>
                    ?: error("Expected ${type.name} to be represented as a map")
                ObjectValue.newObjectValue()
                    .objectFields(
                        type.fieldDefinitions.mapNotNull { field ->
                            if (!values.containsKey(field.name)) return@mapNotNull null
                            ObjectField.newObjectField()
                                .name(field.name)
                                .value(literalFor(values[field.name], field.type))
                                .build()
                        }
                    )
                    .build()
            }
            type is GraphQLEnumType -> EnumValue.newEnumValue()
                .name(type.serialize(value, ctx, locale).toString())
                .build()
            type is GraphQLScalarType -> type.coercing.valueToLiteral(value, ctx, locale)
            else -> error("Unsupported input type $type")
        }
}
