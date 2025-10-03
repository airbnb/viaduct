package viaduct.graphql.utils

import graphql.language.AbstractNode
import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.Node
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.util.TraversalControl
import graphql.util.Traverser
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub

fun Value<*>.rawValue(variables: Map<String, Any?> = mapOf()): Any? {
    return when (this) {
        is StringValue -> value
        is IntValue -> value.toInt()
        is BooleanValue -> isValue
        is FloatValue -> value.toFloat()
        is NullValue -> null
        is ArrayValue -> values.map { it.rawValue(variables) }
        is ObjectValue ->
            objectFields.map {
                it.name to it.value.rawValue(variables)
            }.toMap()
        is EnumValue -> name
        is VariableReference -> variables[name]
        else -> throw UnrecognizedValueTypeException("Value does not have a raw equivalent: $this")
    }
}

fun AbstractNode<*>.collectVariableReferences(): Set<String> {
    val visitor = object : TraverserVisitorStub<Node<*>>() {
        val variableReferences = mutableSetOf<String>()

        override fun enter(context: TraverserContext<Node<*>>): TraversalControl =
            TraversalControl.CONTINUE.also {
                val node = context.thisNode()
                if (node is VariableReference) variableReferences += node.name
            }
    }
    Traverser.depthFirst { n: Node<*> -> n.children }
        .traverse(this, visitor)
    return visitor.variableReferences
}

class UnrecognizedValueTypeException(message: String? = null, cause: Throwable? = null) :
    java.lang.RuntimeException(message, cause)

/**
 * Information about variable usage in selections.
 */
data class VariableUsageInfo(
    val fieldName: String,
    val argumentName: String,
    val type: GraphQLType,
    val hasDefaultValue: Boolean = false,
)

// Just semantics for the below
private typealias TraversalContext = VariableUsageInfo

fun AbstractNode<*>.collectVariableUsages(
    schema: GraphQLSchema,
    variableName: String,
    typeName: String,
): Set<VariableUsageInfo> {
    val visitor = object : TraverserVisitorStub<Node<*>>() {
        val usages = mutableSetOf<VariableUsageInfo>()
        val contextStack = mutableListOf<TraversalContext>()

        override fun enter(context: TraverserContext<Node<*>>): TraversalControl {
            val node = context.thisNode()

            when (node) {
                is Argument -> {
                    val parentNode = context.getParentNode()

                    when (parentNode) {
                        is graphql.language.Field -> {
                            val parentType = when (typeName) {
                                "Query" -> schema.queryType
                                "Mutation" -> schema.mutationType
                                "Subscription" -> schema.subscriptionType
                                else -> null
                            }

                            val fieldDef = parentType?.getFieldDefinition(parentNode.name)
                            fieldDef?.getArgument(node.name)?.let { argumentDef ->
                                contextStack.add(
                                    TraversalContext(
                                        type = argumentDef.type,
                                        fieldName = parentNode.name,
                                        argumentName = node.name,
                                        hasDefaultValue = argumentDef.hasSetDefaultValue()
                                    )
                                )
                            }
                        }

                        is Directive -> {
                            val directiveDef = schema.getDirective(parentNode.name)
                            directiveDef?.getArgument(node.name)?.let { argumentDef ->
                                contextStack.add(
                                    TraversalContext(
                                        type = argumentDef.type,
                                        fieldName = "directive:${parentNode.name}",
                                        argumentName = node.name,
                                        hasDefaultValue = argumentDef.hasSetDefaultValue()
                                    )
                                )
                            }
                        }
                    }
                }

                is ObjectField -> {
                    // Get current context from stack
                    val currentContext = contextStack.lastOrNull()
                    if (currentContext != null) {
                        val unwrappedType = GraphQLTypeUtil.unwrapAll(currentContext.type)
                        if (unwrappedType is GraphQLInputObjectType) {
                            unwrappedType.getFieldDefinition(node.name)?.let { fieldDef ->
                                contextStack.add(
                                    TraversalContext(
                                        type = fieldDef.type,
                                        fieldName = currentContext.fieldName,
                                        argumentName = currentContext.argumentName,
                                        hasDefaultValue = currentContext.hasDefaultValue,
                                    )
                                )
                            }
                        }
                    }
                }

                is VariableReference -> {
                    if (node.name == variableName) {
                        contextStack.lastOrNull()?.let { usages.add(it) }
                    }
                }
            }

            return TraversalControl.CONTINUE
        }

        override fun leave(context: TraverserContext<Node<*>>): TraversalControl {
            val node = context.thisNode()

            // Pop context when leaving nodes that pushed context
            when (node) {
                is Argument, is ObjectField -> {
                    if (contextStack.isNotEmpty()) {
                        contextStack.removeAt(contextStack.lastIndex)
                    }
                }
            }

            return TraversalControl.CONTINUE
        }
    }
    Traverser.depthFirst { n: Node<*> -> n.children }.traverse(this, visitor)
    return visitor.usages
}
