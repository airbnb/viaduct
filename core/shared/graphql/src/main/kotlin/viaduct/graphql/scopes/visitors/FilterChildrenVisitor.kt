package viaduct.graphql.scopes.visitors

import graphql.language.NamedNode
import graphql.language.TypeName
import graphql.schema.GraphQLNamedInputType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchemaElement
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.utils.ElementScopeMetadata
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.getChildrenForElement
import viaduct.graphql.scopes.utils.isIntrospectionField

internal class FilterChildrenVisitor(
    private val appliedScopes: Set<String>,
    private val scopeDirectiveParser: ScopeDirectiveParser,
    private val elementChildren: MutableMap<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>,
    private val includeTenantLocalFields: Boolean = false,
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

    /**
     * For the (applicable) children of a given node (e.g. fields, enum values, member types), filter those
     * children based on their name and the scopes that are applied to that node (both the root node and
     * its extensions).
     */
    private fun filterChildren(context: TraverserContext<GraphQLSchemaElement>) {
        val element = context.thisNode()

        // we should only be visiting named elements
        if (element !is GraphQLNamedSchemaElement) {
            return
        }

        val children = getChildrenForElement(element) ?: return

        // Build an object containing which child elements are part of each scope (all scopes)
        val metadata = scopeDirectiveParser.metadataForElement(element) ?: return

        // Get the element names in the _applied_ scopes
        val elementNamesInAppliedScopes =
            getElementNamesInScopes(metadata, appliedScopes, includeTenantLocalFields)
        val newChildElements = children.filter { el ->
            elementNamesInAppliedScopes.contains(getKeyForElement(el))
        }

        elementChildren[element] = newChildElements
    }

    /**
     * Fold over the scope list and get a union of all field names from the scope metadata
     * that are visible for those scopes.
     **/
    private fun getElementNamesInScopes(
        elementScopeMetadata: ElementScopeMetadata,
        scopes: Set<String>,
        includeTenantLocalFields: Boolean,
    ) = scopes.fold(setOf<String>()) { acc, scope ->
        val ordinaryElements = elementScopeMetadata.elementsForScopes[scope].orEmpty()
        val tenantLocalElements =
            if (includeTenantLocalFields) {
                elementScopeMetadata.tenantLocalElementsForScopes[scope].orEmpty()
            } else {
                emptyList()
            }
        acc + (ordinaryElements + tenantLocalElements).map(::getKeyForNode)
    }

    private fun getKeyForNode(node: NamedNode<*>): String =
        if (node is TypeName) {
            "Type__${node.name}"
        } else {
            "Member__${node.name}"
        }

    private fun getKeyForElement(node: GraphQLNamedSchemaElement): String =
        if (node is GraphQLNamedOutputType || node is GraphQLNamedInputType) {
            "Type__${node.name}"
        } else {
            "Member__${node.name}"
        }
}
