package viaduct.graphql.scopes.visitors

import graphql.language.NamedNode
import graphql.language.TypeName
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedInputType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.utils.ElementScopeMetadata
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.getChildrenForElement
import viaduct.graphql.scopes.utils.isIntrospectionField

internal class FilterChildrenVisitor(
    private val schema: GraphQLSchema,
    private val appliedScopes: Set<String>,
    private val scopeDirectiveParser: ScopeDirectiveParser,
    private val elementChildren: MutableMap<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>,
) : TraverserVisitorStub<GraphQLSchemaElement>() {
    private val retainedInterfaceNamesCache = mutableMapOf<String, Set<String>>()
    private val retainedInterfaceNamesInProgress = mutableSetOf<String>()

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
        val scopedChildElements = getScopedChildrenForElement(element, children, metadata)
        val newChildElements = filterUnsatisfiedInterfaces(scopedChildElements)

        elementChildren[element] = newChildElements
    }

    private fun filterUnsatisfiedInterfaces(children: List<GraphQLNamedSchemaElement>): List<GraphQLNamedSchemaElement> {
        val fieldsByName = children
            .filterIsInstance<GraphQLFieldDefinition>()
            .associateBy { it.name }

        return children.filter { child ->
            child !is GraphQLInterfaceType ||
                isInterfaceSatisfiedByFields(child, fieldsByName)
        }
    }

    private fun isInterfaceSatisfiedByFields(
        graphQLInterfaceType: GraphQLInterfaceType,
        fieldsByName: Map<String, GraphQLFieldDefinition>,
    ): Boolean =
        graphQLInterfaceType.fieldDefinitions.all { interfaceField ->
            val field = fieldsByName[interfaceField.name] ?: return@all false
            isOutputTypeCompatible(field.type, interfaceField.type)
        }

    private fun isOutputTypeCompatible(
        actual: GraphQLOutputType,
        expected: GraphQLOutputType
    ): Boolean {
        if (expected is GraphQLNonNull) {
            return actual is GraphQLNonNull &&
                isOutputTypeCompatible(actual.wrappedType as GraphQLOutputType, expected.wrappedType as GraphQLOutputType)
        }
        if (actual is GraphQLNonNull) {
            return isOutputTypeCompatible(actual.wrappedType as GraphQLOutputType, expected)
        }
        if (expected is GraphQLList) {
            return actual is GraphQLList &&
                isOutputTypeCompatible(actual.wrappedType as GraphQLOutputType, expected.wrappedType as GraphQLOutputType)
        }
        if (actual is GraphQLList) {
            return false
        }

        val actualNamedType = resolveNamedType(actual)
        val expectedNamedType = resolveNamedType(expected)
        if (actualNamedType?.name == expectedNamedType?.name) {
            return true
        }

        return when (expectedNamedType) {
            is GraphQLInterfaceType ->
                actualNamedType is GraphQLImplementingType &&
                    retainedInterfaceNamesForType(actualNamedType).contains(expectedNamedType.name)
            is GraphQLUnionType ->
                actualNamedType is GraphQLObjectType &&
                    expectedNamedType.types.any { it.name == actualNamedType.name }
            else -> false
        }
    }

    private fun retainedInterfaceNamesForType(type: GraphQLImplementingType): Set<String> {
        val typeName = (type as GraphQLNamedType).name
        retainedInterfaceNamesCache[typeName]?.let { return it }

        // Break recursive interface cycles conservatively by falling back to declared interface names.
        if (!retainedInterfaceNamesInProgress.add(typeName)) {
            return type.interfaces.map { it.name }.toSet()
        }

        val children = getScopedChildrenForElement(type)
        val retainedInterfaceNames = children
            .filterIsInstance<GraphQLInterfaceType>()
            .filter { graphQLInterfaceType ->
                val fieldsByName = children.filterIsInstance<GraphQLFieldDefinition>().associateBy { it.name }
                isInterfaceSatisfiedByFields(graphQLInterfaceType, fieldsByName)
            }
            .map { it.name }
            .toSet()

        retainedInterfaceNamesInProgress.remove(typeName)
        retainedInterfaceNamesCache[typeName] = retainedInterfaceNames
        return retainedInterfaceNames
    }

    private fun getScopedChildrenForElement(
        element: GraphQLNamedSchemaElement,
        children: List<GraphQLNamedSchemaElement> = getResolvedChildrenForElement(element),
        metadata: ElementScopeMetadata? = scopeDirectiveParser.metadataForElement(element),
    ): List<GraphQLNamedSchemaElement> {
        val elementNamesInAppliedScopes = metadata?.let { getElementNamesInScopes(it, appliedScopes) } ?: return children
        return children.filter { el ->
            elementNamesInAppliedScopes.contains(getKeyForElement(el))
        }
    }

    private fun getResolvedChildrenForElement(element: GraphQLNamedSchemaElement): List<GraphQLNamedSchemaElement> =
        getChildrenForElement(element)
            ?.mapNotNull { resolveNamedSchemaElement(it) }
            ?: emptyList()

    private fun resolveNamedSchemaElement(element: GraphQLNamedSchemaElement): GraphQLNamedSchemaElement? =
        if (element is GraphQLTypeReference) {
            schema.typeMap[element.name]
        } else {
            element
        }

    private fun resolveNamedType(type: GraphQLOutputType): GraphQLNamedType? {
        val unwrapped = GraphQLTypeUtil.unwrapAll(type)
        return if (unwrapped is GraphQLTypeReference) {
            schema.typeMap[unwrapped.getName()]
        } else {
            unwrapped
        }
    }

    /**
     * Fold over the scope list and get a union of all field names from the scope metadata
     * that are visible for those scopes.
     **/
    private fun getElementNamesInScopes(
        elementScopeMetadata: ElementScopeMetadata,
        scopes: Set<String>,
    ) = scopes.fold(setOf<String>()) { acc, scope ->
        val elementNamesForScope =
            elementScopeMetadata.elementsForScopes[scope]?.map { getKeyForNode(it) }?.toSet()
                ?: setOf()
        acc + elementNamesForScope
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
