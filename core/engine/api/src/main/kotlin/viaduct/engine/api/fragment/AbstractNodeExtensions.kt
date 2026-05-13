package viaduct.engine.api.fragment

import graphql.language.AbstractNode
import graphql.language.Argument
import graphql.language.Node
import graphql.language.NodeTraverser
import graphql.language.NodeVisitorStub
import graphql.language.VariableReference
import graphql.util.TraversalControl
import graphql.util.TraverserContext

/**
 * Collects all [VariableReference] nodes reachable from this AST node, keyed by variable name.
 *
 * Delegates to the [List] overload with a single-element list.
 */
fun AbstractNode<*>.allVariableReferencesByName() = listOf(this).allVariableReferencesByName()

/**
 * Traverses the AST rooted at each node in this list and returns a map from variable name to
 * the corresponding [VariableReference] node for every `$variable` reference found inside
 * field argument values.
 *
 * Only argument-level variable references are collected; variable references in other positions
 * (e.g. directive arguments) are not included.
 */
@Suppress("UNCHECKED_CAST")
fun List<AbstractNode<*>>.allVariableReferencesByName(): Map<String, VariableReference> =
    NodeTraverser().postOrder(
        object : NodeVisitorStub() {
            override fun visitArgument(
                node: Argument,
                context: TraverserContext<Node<*>>
            ): TraversalControl {
                val value = node.value
                if (value is VariableReference) {
                    val acc = context.getCurrentAccumulate<Map<String, VariableReference>>() ?: mapOf()
                    context.setAccumulate(acc + mapOf(value.name to value))
                }
                return super.visitArgument(node, context)
            }
        },
        this
    ) as Map<String, VariableReference>? ?: mapOf()
