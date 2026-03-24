package viaduct.engine.api.fragment

import graphql.language.AbstractNode
import graphql.language.Argument
import graphql.language.Node
import graphql.language.NodeTraverser
import graphql.language.NodeVisitorStub
import graphql.language.VariableReference
import graphql.util.TraversalControl
import graphql.util.TraverserContext

fun AbstractNode<*>.allVariableReferencesByName() = listOf(this).allVariableReferencesByName()

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
