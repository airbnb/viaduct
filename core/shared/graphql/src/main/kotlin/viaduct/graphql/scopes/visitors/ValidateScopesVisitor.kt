package viaduct.graphql.scopes.visitors

import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchemaElement
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.errors.DirectiveRetainedTypeScopeError
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.isIntrospectionField

/**
 * This Visitor validates structural properties of how scopes are applied
 */
internal class ValidateScopesVisitor(
    private val validScopes: Set<String>,
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
     * Because directives may not be applied to directive types, then all directive definitions must exist in all scopes
     * If a given type is a transitive dependency of a directive, then that type must also exist in all scopes.
     *
     * This method checks that any type that is transitively used by a directive is available in all scopes.
     */
    private fun validateDirectiveRetention(context: TraverserContext<GraphQLSchemaElement>) {
        val element = context.thisNode()

        // we should only be visiting named elements
        if (element !is GraphQLNamedSchemaElement) {
            return
        }

        if (retainedByDirective(context)) {
            val metadata = scopeDirectiveParser.metadataForElement(element)
            val scopes = metadata?.scopesForType() ?: return

            if (scopes != validScopes) {
                throw DirectiveRetainedTypeScopeError(element)
            }
        }
    }

    private fun retainedByDirective(context: TraverserContext<GraphQLSchemaElement>): Boolean =
        context.parentNodes.any {
            it is GraphQLDirective || it is GraphQLAppliedDirective
        }
}
