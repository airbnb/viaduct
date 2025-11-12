package viaduct.graphql.utils

import graphql.analysis.QueryVisitorFieldArgumentValueEnvironment
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorFragmentSpreadEnvironment
import graphql.analysis.QueryVisitorInlineFragmentEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.util.TraversalControl

/**
 * Collects VariableUsageInfo for each variable reference. Variables can appear in several places:
 * 1. Output field arguments, e.g. { foo(a: $var) }
 * 2. Input object fields, e.g. { foo(a: { b: $var }) }
 * 3. Directive arguments, e.g. { foo @dir(a: $var) }
 */
internal class VariableUsageInfoVisitor(
    private val schema: GraphQLSchema
) : QueryVisitorStub() {
    private val usages = mutableMapOf<String, MutableSet<VariableUsageInfo>>()

    fun getUsages(): Map<String, Set<VariableUsageInfo>> = usages

    override fun visitField(env: QueryVisitorFieldEnvironment) {
        processDirectives(env.field.directives)
    }

    override fun visitFragmentSpread(env: QueryVisitorFragmentSpreadEnvironment) {
        processDirectives(env.fragmentSpread.directives)
    }

    override fun visitInlineFragment(env: QueryVisitorInlineFragmentEnvironment) {
        processDirectives(env.inlineFragment.directives)
    }

    override fun visitArgumentValue(env: QueryVisitorFieldArgumentValueEnvironment): TraversalControl {
        val value = env.argumentInputValue.value
        if (value is VariableReference) {
            val inputValueDefinition = env.argumentInputValue.inputValueDefinition
            val usageInfo = when (inputValueDefinition) {
                is GraphQLArgument -> VariableUsageInfo(
                    inputValueDefinition.type,
                    inputValueDefinition.hasSetDefaultValue(),
                    "field '${env.fieldDefinition.name}', argument '${env.graphQLArgument.name}'"
                )
                is GraphQLInputObjectField -> {
                    // For nested input object fields, get the parent's type
                    val parent = env.argumentInputValue.parent
                    val inputType = GraphQLTypeUtil.unwrapAll(parent.inputType) as GraphQLInputObjectType
                    VariableUsageInfo(
                        inputValueDefinition.type,
                        inputValueDefinition.hasSetDefaultValue(),
                        "input field '${inputType.name}.${inputValueDefinition.name}'"
                    )
                }
                else -> throw IllegalArgumentException(
                    "Unexpected GraphQLInputValueDefinition type: ${inputValueDefinition::class.qualifiedName}"
                )
            }

            usages.getOrPut(value.name) { mutableSetOf() }.add(usageInfo)
        }
        return TraversalControl.CONTINUE
    }

    /**
     * QueryVisitor doesn't traverse into directive argument values, so manually traverse
     * into them and collect usages here
     */
    private fun processDirectives(directives: List<Directive>) {
        directives.forEach { directive -> processDirective(directive) }
    }

    private fun processDirective(directive: Directive) {
        val directiveDef = checkNotNull(schema.getDirective(directive.name)) { "Directive ${directive.name} not found" }
        directive.arguments.forEach { argument ->
            val argumentDef = checkNotNull(directiveDef.getArgument(argument.name)) { "Argument ${argument.name} not found on directive ${directive.name}" }
            processDirectiveArgumentValue(
                value = argument.value,
                type = argumentDef.type,
                contextString = "directive '${directive.name}', argument '${argument.name}'",
                hasDefaultValue = argumentDef.hasSetDefaultValue()
            )
        }
    }

    /**
     * Recursively process a directive argument value to find all variable references.
     */
    private fun processDirectiveArgumentValue(
        value: Value<*>,
        type: GraphQLInputType,
        hasDefaultValue: Boolean,
        contextString: String,
    ) {
        when (value) {
            is VariableReference -> {
                usages.getOrPut(value.name) { mutableSetOf() }.add(
                    VariableUsageInfo(type, hasDefaultValue, contextString)
                )
            }
            is ObjectValue -> {
                val inputObjectType = checkNotNull(GraphQLTypeUtil.unwrapAll(type) as? GraphQLInputObjectType) {
                    "Expected ObjectValue to have corresponding input object type, was $type"
                }
                value.objectFields.forEach { objectField ->
                    val fieldDef = checkNotNull(inputObjectType.getField(objectField.name)) {
                        "Field '${objectField.name} not found on input type ${inputObjectType.name}"
                    }
                    processDirectiveArgumentValue(
                        value = objectField.value,
                        type = fieldDef.type,
                        hasDefaultValue = fieldDef.hasSetDefaultValue(),
                        contextString = "input field '${inputObjectType.name}.${fieldDef.name}'",
                    )
                }
            }
            is ArrayValue -> {
                val unwrapped = GraphQLTypeUtil.unwrapNonNull(type)
                require(unwrapped is GraphQLList) {
                    "Expected ArrayValue to have corresponding GraphQLList type, but was ${unwrapped::class.qualifiedName}"
                }
                val elementType = unwrapped.wrappedType as GraphQLInputType
                value.values.forEach { elementValue ->
                    processDirectiveArgumentValue(
                        value = elementValue,
                        type = elementType,
                        hasDefaultValue = false,
                        contextString = contextString,
                    )
                }
            }
            // For scalars, enums, nulls - do nothing (no variables to collect)
        }
    }
}
