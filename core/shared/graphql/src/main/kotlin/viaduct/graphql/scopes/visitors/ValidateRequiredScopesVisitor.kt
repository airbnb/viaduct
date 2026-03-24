package viaduct.graphql.scopes.visitors

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchemaElement
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.isIntrospectionField

/**
 * This Visitor validates scope directives applied
 */
internal class ValidateRequiredScopesVisitor(
    private val scopeDirectiveParser: ScopeDirectiveParser
) : TraverserVisitorStub<GraphQLSchemaElement>() {
    override fun enter(context: TraverserContext<GraphQLSchemaElement>): TraversalControl {
        if (isIntrospectionField(context.thisNode())) {
            return TraversalControl.ABORT
        }
        if (!canHaveScopeApplied(context.thisNode())) {
            return TraversalControl.CONTINUE
        }
        validateDirectiveRetention(context)
        return TraversalControl.CONTINUE
    }

    /**
     * Directives must be applied to root types (Query, Mutation, Subscription)
     */
    private fun validateDirectiveRetention(context: TraverserContext<GraphQLSchemaElement>) {
        val element = (context.thisNode() as? GraphQLObjectType) ?: return

        if (isValidationScopeRequired(context)) {
            scopeDirectiveParser.metadataForElement(element)
        }
    }

    /**
     * Directives must be applied to root types (Query, Mutation, Subscription)
     */
    private fun isValidationScopeRequired(context: TraverserContext<GraphQLSchemaElement>): Boolean {
        val element = (context.thisNode() as? GraphQLObjectType) ?: return false

        if (element.name !in listOf("Query", "Mutation", "Subscription")) {
            return false
        }

        return context.parentNodes.any {
            it is StubRoot
        }
    }
}
