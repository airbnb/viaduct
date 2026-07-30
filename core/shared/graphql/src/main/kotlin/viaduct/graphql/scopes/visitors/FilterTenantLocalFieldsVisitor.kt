package viaduct.graphql.scopes.visitors

import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchemaElement
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.getChildrenForElement
import viaduct.graphql.scopes.utils.isIntrospectionField
import viaduct.graphql.scopes.utils.isTenantLocalEquivalentField

internal class FilterTenantLocalFieldsVisitor(
    private val elementChildren: MutableMap<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>
) : TraverserVisitorStub<GraphQLSchemaElement>() {
    override fun enter(context: TraverserContext<GraphQLSchemaElement>): TraversalControl {
        if (isIntrospectionField(context.thisNode())) {
            return TraversalControl.ABORT
        }
        if (!canHaveScopeApplied(context.thisNode())) {
            return TraversalControl.CONTINUE
        }
        filterChildren(context)
        return TraversalControl.CONTINUE
    }

    private fun filterChildren(context: TraverserContext<GraphQLSchemaElement>) {
        val element = context.thisNode()
        if (element !is GraphQLNamedSchemaElement) {
            return
        }

        val children = getChildrenForElement(element) ?: return
        elementChildren[element] = children.filterNot(::isTenantLocalEquivalentField)
    }
}
