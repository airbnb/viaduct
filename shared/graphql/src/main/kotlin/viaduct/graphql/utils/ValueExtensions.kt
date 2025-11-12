package viaduct.graphql.utils

import graphql.analysis.QueryTraversalOptions
import graphql.language.AbstractNode
import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.FragmentDefinition
import graphql.language.IntValue
import graphql.language.ListType
import graphql.language.Node
import graphql.language.NonNullType
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.Value
import graphql.language.VariableDefinition
import graphql.language.VariableReference
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
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
 *
 * @param type The type of the argument or input field where the variable appears
 * @param hasDefaultValue Whether or not the argument or input field has a default value
 * @param contextString Contextual information about where the variable appears, used for error messages. Examples:
 *        - field 'foo', argument 'arg'
 *        - input field 'Foo.bar'
 *        - directive 'dir', argument 'arg'
 */
data class VariableUsageInfo(
    val type: GraphQLInputType,
    val hasDefaultValue: Boolean,
    val contextString: String
)

/**
 * Traverses the node to find all variable references and constructs a [VariableUsageInfo] for each
 *
 * @param typeName The name of the root type, useful for situations where the root type isn't present in the AbstractNode,
 *        e.g. if it's just a SelectionSet.
 * @param fragmentDefinitions Fragment definitions to traverse when collecting variables from fragment spreads
 */
fun AbstractNode<*>.collectAllVariableUsages(
    schema: GraphQLSchema,
    typeName: String,
    fragmentDefinitions: Map<String, FragmentDefinition> = emptyMap()
): Map<String, Set<VariableUsageInfo>> {
    val rootParentType = checkNotNull(schema.getType(typeName) as? GraphQLCompositeType) {
        "Expected parameter typeName passed to collectAllVariableUsages to be a composite type, was $typeName"
    }

    val visitor = VariableUsageInfoVisitor(schema)

    ViaductQueryTraverser.newQueryTraverser()
        .schema(schema)
        .root(this as Node<*>)
        .rootParentType(rootParentType)
        .fragmentsByName(fragmentDefinitions)
        .options(QueryTraversalOptions.defaultOptions().coerceFieldArguments(false))
        .build()
        .visitPostOrder(visitor)

    return visitor.getUsages()
}

/**
 * Creates a [VariableUsageInfo] for each occurrence of a given variable
 *
 * @param variableName The name of the variable to collect usage info for
 * @param typeName The name of the root type, useful for situations where the root type isn't present in the AbstractNode,
 *        e.g. if it's just a SelectionSet.
 * @param fragmentDefinitions Fragment definitions to traverse when collecting variables from fragment spreads
 *
 */
fun AbstractNode<*>.collectVariableUsages(
    schema: GraphQLSchema,
    variableName: String,
    typeName: String,
    fragmentDefinitions: Map<String, FragmentDefinition> = emptyMap()
): Set<VariableUsageInfo> = collectAllVariableUsages(schema, typeName, fragmentDefinitions)[variableName] ?: emptySet()

/**
 * Create one VariableDefinition for each variable name in the node. If a variable is used in multiple
 * locations, this requires that all locations are structurally the same, and takes the combined
 * strictest nullability requirements of these locations.
 *
 * Note that the GraphQL spec is more relaxed than this function. For example, if a variable appears in
 * a location of type `String` and `[String]`, theoretically this variable definition's type could be `String`,
 * but this is currently unsupported and it will throw since they're structurally different.
 *
 * @param typeName The name of the root type, useful for situations where the root type isn't present in the AbstractNode,
 *        e.g. if it's just a SelectionSet.
 * @param fragmentDefinitions Fragment definitions to traverse when collecting variables from fragment spreads
 */
fun AbstractNode<*>.collectVariableDefinitions(
    schema: GraphQLSchema,
    typeName: String,
    fragmentDefinitions: Map<String, FragmentDefinition> = emptyMap()
): List<VariableDefinition> {
    return collectAllVariableUsages(schema, typeName, fragmentDefinitions)
        .map { (varName, usages) ->
            val combinedType = usages.map { it.type }.reduce { acc, type -> combineNullabilityRequirements(acc, type) }
            VariableDefinition.newVariableDefinition()
                .name(varName)
                .type(combinedType.toASTType())
                .build()
        }
}

/**
 * Compares two GraphQLInputTypes and returns the combined nullability requirements, prioritizing non-nullability.
 * Note that this does not take default values into consideration, something we may want to revisit
 * if we allow unset variables for required selection sets.
 *
 * Examples:
 * - combineNullabilityRequirements(String, String!) = String!
 * - combineNullabilityRequirements([String!], [String]!) = [String!]!
 */
internal fun combineNullabilityRequirements(
    type1: GraphQLInputType,
    type2: GraphQLInputType
): GraphQLInputType {
    val type1Unwrapped = GraphQLTypeUtil.unwrapNonNull(type1) as GraphQLInputType
    val type2Unwrapped = GraphQLTypeUtil.unwrapNonNull(type2) as GraphQLInputType

    val resultInner = when {
        type1Unwrapped is GraphQLList && type2Unwrapped is GraphQLList -> {
            val elem1 = type1Unwrapped.wrappedType as GraphQLInputType
            val elem2 = type2Unwrapped.wrappedType as GraphQLInputType
            GraphQLList.list(combineNullabilityRequirements(elem1, elem2))
        }
        type1Unwrapped == type2Unwrapped -> type1Unwrapped
        else -> throw IllegalStateException("$type1 and $type2 are structurally incompatible")
    }

    // the result should be non-null if either type is non-null
    return if (type1 is GraphQLNonNull || type2 is GraphQLNonNull) {
        GraphQLNonNull.nonNull(resultInner)
    } else {
        resultInner
    }
}

/**
 * Converts a graphql.schema.GraphQLType to a graphql.language.Type (AST type)
 */
private fun GraphQLType.toASTType(): Type<*> {
    return when (this) {
        is GraphQLNonNull -> NonNullType.newNonNullType(this.wrappedType.toASTType()).build()
        is GraphQLList -> ListType.newListType(this.wrappedType.toASTType()).build()
        is GraphQLNamedType -> TypeName.newTypeName(this.name).build()
        else -> throw IllegalArgumentException("Unsupported GraphQLType: $this")
    }
}
