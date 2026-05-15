package viaduct.graphql.scopes.visitors

import graphql.language.ArrayValue
import graphql.language.DirectivesContainer
import graphql.language.StringValue
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
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
     * This method checks that any type that is transitively used by a directive uses the literal wildcard ["*"],
     * which is the only acceptable scope annotation for directive-retained types (A.8).
     */
    private fun validateDirectiveRetention(context: TraverserContext<GraphQLSchemaElement>) {
        val element = context.thisNode()

        // we should only be visiting named elements
        if (element !is GraphQLNamedSchemaElement) {
            return
        }

        if (retainedByDirective(context)) {
            val metadata = scopeDirectiveParser.metadataForElement(element)
            // null means the type is not scope-able (e.g. scalar) — skip
            metadata?.scopesForType() ?: return

            // A.8: only the literal ["*"] wildcard is acceptable for directive-retained types;
            // enumerating the full scope universe explicitly is rejected.
            if (!hasLiteralWildcardScope(element)) {
                throw DirectiveRetainedTypeScopeError(element)
            }
        }
    }

    private fun hasLiteralWildcardScope(element: GraphQLNamedSchemaElement): Boolean {
        val container = element as? GraphQLDirectiveContainer ?: return false
        val definition = container.definition as? DirectivesContainer<*> ?: return false
        val scopeDir = definition.getDirectives("scope").firstOrNull() ?: return false
        val toValue = scopeDir.getArgument("to")?.value as? ArrayValue ?: return false
        return toValue.values.size == 1 && (toValue.values[0] as? StringValue)?.value == "*"
    }

    private fun retainedByDirective(context: TraverserContext<GraphQLSchemaElement>): Boolean =
        context.parentNodes.any {
            it is GraphQLDirective || it is GraphQLAppliedDirective
        }
}
