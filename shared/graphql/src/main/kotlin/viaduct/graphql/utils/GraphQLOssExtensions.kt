package viaduct.graphql.utils

import graphql.language.Node
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil

fun GraphQLType.asNamedElement() =
    this.asMaybeNamedElement()
        ?: throw RuntimeException("$this is not a named element; is a ${this.javaClass}.")

fun GraphQLType.asMaybeNamedElement() =
    this as? GraphQLNamedSchemaElement
        ?: GraphQLTypeUtil.unwrapAll(this) as? GraphQLNamedSchemaElement

/**
 * Return a flattened view of all children under this Node.
 * This method does not traverse through FragmentSpreads or VariableReferences.
 */
val Node<*>.allChildren: List<Node<*>> get() = children.fold(children.toList()) { acc, c ->
    acc + c.allChildren
}

/**
 * Return a flattened view of all children under this Node with the provided Node type.
 * This method does not traverse through FragmentSpreads or VariableReferences.
 */
inline fun <reified T : Node<*>> Node<*>.allChildrenOfType(): List<T> = allChildren.filterIsInstance<T>()
