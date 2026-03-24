package viaduct.graphql.scopes.visitors

import graphql.schema.GraphQLSchemaElement
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitor
import graphql.util.TraverserVisitorStub

class CompositeVisitor(
    vararg visitors: TraverserVisitor<GraphQLSchemaElement>
) : TraverserVisitorStub<GraphQLSchemaElement>() {
    private val visitors = visitors.toList()

    override fun enter(context: TraverserContext<GraphQLSchemaElement>?): TraversalControl {
        visitors.forEach {
            val result = it.enter(context)
            if (result != TraversalControl.CONTINUE) {
                return result
            }
        }
        return super.enter(context)
    }

    override fun leave(context: TraverserContext<GraphQLSchemaElement>?): TraversalControl {
        visitors.forEach {
            val result = it.leave(context)
            if (result != TraversalControl.CONTINUE) {
                return result
            }
        }
        return super.leave(context)
    }
}
