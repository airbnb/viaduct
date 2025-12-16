@file:Suppress("FunctionName")

package viaduct.graphql.scopes.utils

import graphql.language.ArrayValue
import graphql.language.DirectivesContainer
import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.StringValue
import graphql.language.UnionTypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeUtil.unwrapAll
import graphql.schema.GraphQLUnionType
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.utils.slf4j.logger

internal class ScopeDirectiveParser(
    private val validScopeNames: Set<String>
) {
    companion object {
        private val log by logger()

        private const val SCOPED_TO_ARG: String = "to"
        private const val WILDCARD_SCOPE: String = "*"
    }

    fun metadataForElement(element: GraphQLSchemaElement): ElementScopeMetadata? {
        if (element !is GraphQLNamedSchemaElement) {
            return null
        }

        val metadata = metadataForElementNoValidation(element)

        // Validate the child elements by looping through and ensuring that the intersection set of this element's
        // scopes and the element a given child references is at >= 1

        // Skip enum types for this validation...they can't reference other types
        if (element is GraphQLEnumType) {
            return metadata
        }

        // Validate the children of this element
        getChildrenForElement(element)?.forEach { child ->
            val referencedType =
                when (child) {
                    is GraphQLFieldDefinition -> unwrapAll(child.type)
                    is GraphQLInputObjectField -> unwrapAll(child.type)
                    is GraphQLNamedOutputType -> child
                    else -> return@forEach
                }
            if (!canHaveScopeApplied(referencedType)) {
                return@forEach
            }

            // ensure referenced type specifies _some_ scope
            val scopesForReferencedType =
                metadataForElementNoValidation(referencedType)?.scopesForType()
                    ?: throw SchemaScopeValidationError(
                        "Type '${element.name}' references type '${referencedType.name}' which does not specify any " +
                            "scopes. Please apply scopes to the referenced type '${referencedType.name}'.",
                        element.definition
                    )

            // if there's no scopes in common between this type and the referenced type, we know its invalid
            val intersection = metadata!!.scopesForType() intersect scopesForReferencedType
            if (intersection.isEmpty()) {
                throw SchemaScopeValidationError(
                    "Type '${element.name}' references type '${referencedType.name}', but they do not have any " +
                        "scopes in common. Therefore, the field or member that references this type reference would " +
                        "never be visible in any scope. Please ensure that '${element.name}' and " +
                        "'${referencedType.name}' share at least one scope, or remove the reference.",
                    element.definition
                )
            }
        }
        return metadata
    }

    private val memoizedMetadata = mutableMapOf<GraphQLNamedSchemaElement, ElementScopeMetadata?>()

    private fun metadataForElementNoValidation(element: GraphQLNamedSchemaElement) =
        memoizedMetadata.computeIfAbsent(element) {
            when (element) {
                is GraphQLObjectType, is GraphQLInputObjectType, is GraphQLInterfaceType ->
                    _metadataForScopeAllowedElementHelper(element as GraphQLDirectiveContainer)
                is GraphQLEnumType ->
                    _metadataForScopeAllowedElementHelper(element)
                is GraphQLUnionType ->
                    _metadataForScopeAllowedElementHelper(element)
                else -> null
            }
        }

    /**
     * A helper method to return scope metadata for GraphQL elements that allow @scope directive.
     *
     * @throws SchemaScopeValidationError if the given element does not have a @scope directive, or the scope values
     *     defined under the root do not contain all the scope values defined in all its type extensions.
     */
    private fun _metadataForScopeAllowedElementHelper(element: GraphQLDirectiveContainer): ElementScopeMetadata {
        val hasScopeDirective = element.hasAppliedDirective("scope")
        if (!hasScopeDirective) {
            throw SchemaScopeValidationError(
                "No scope directive found for element with name: ${element.name}. Please apply proper scopes to it.",
                element.definition
            )
        }

        val childNodes = getChildNodes(element.definition!!)
        val scopesForType = getScopesFromDirective(element, "scope")
        val elementsForScopes =
            mutableMapOf<
                // scope name
                String,
                // field/value/type names
                MutableList<NamedNode<*>>
            >()

        scopesForType.forEach { scope ->
            if (elementsForScopes[scope] == null) {
                elementsForScopes[scope] = mutableListOf()
            }
//            if (element is GraphQLImplementingType) {
//                childNodes.filter { it is TypeName }.forEach { node ->
//                    val interfacesForElement = element.interfaces
//                    val metadataForIface =
//                        metadataForElementNoValidation(interfacesForElement.find { it.name == node.name }
//                            ?: error("Can't find interface with name ${node.name}"))
//                            ?: error("Could not get metadata for interface with name ${node.name}")
//                    val intersection = listOf(scope) intersect metadataForIface.scopesForType()
//                    if (!intersection.isEmpty()) {
//                        elementsForScopes[scope]?.add(node)
//                    }
//                }
//                elementsForScopes[scope]?.addAll(childNodes.filter { it !is TypeName })
//            } else {
            elementsForScopes[scope]?.addAll(childNodes)
//            }
        }
        val extensionDefinitions = getExtensions(element)
        extensionDefinitions?.forEach { node ->
            val extensionChildElementNames = getChildNodes(node)
            val scopesForExtension = getScopesFromDirective(node, "scope")
            // validate the scopes defined in the extensions are defined in the root type definition
            if (!scopesForType.containsAll(scopesForExtension)) {
                // find the scopes that aren't defined in the root type
                val incorrectScopes = scopesForExtension - scopesForType
                throw SchemaScopeValidationError(
                    "The following scope(s) need to be defined in the root definition for type " +
                        "'${element.name}': $incorrectScopes",
                    node
                )
            }
            scopesForExtension.forEach {
                elementsForScopes[it]?.addAll(extensionChildElementNames)
            }
        }

        return ElementScopeMetadata(element.name, elementsForScopes)
    }

    private fun getChildNodes(node: Node<*>): List<NamedNode<*>> =
        when (node) {
            is ObjectTypeExtensionDefinition ->
                node.fieldDefinitions.map { it } +
                    node.implements.map { it as NamedNode<*> }
            is ObjectTypeDefinition ->
                node.fieldDefinitions.map { it } +
                    node.implements.map { it as NamedNode<*> }
            is InterfaceTypeExtensionDefinition ->
                node.fieldDefinitions.map { it } +
                    node.implements.map { it as NamedNode<*> }
            is InterfaceTypeDefinition ->
                node.fieldDefinitions.map { it } +
                    node.implements.map { it as NamedNode<*> }
            is InputObjectTypeExtensionDefinition -> node.inputValueDefinitions.map { it }
            is InputObjectTypeDefinition -> node.inputValueDefinitions.map { it }
            is EnumTypeExtensionDefinition -> node.enumValueDefinitions.map { it }
            is EnumTypeDefinition -> node.enumValueDefinitions.map { it }
            is UnionTypeExtensionDefinition -> node.memberTypes.map { (it as NamedNode<*>) }
            is UnionTypeDefinition -> node.memberTypes.map { (it as NamedNode<*>) }
            else -> throw RuntimeException("Cannot get child elements of type: $node")
        }

    private fun getExtensions(element: GraphQLSchemaElement): List<Node<*>>? =
        when (element) {
            is GraphQLObjectType -> element.extensionDefinitions
            is GraphQLInterfaceType -> element.extensionDefinitions
            is GraphQLInputObjectType -> element.extensionDefinitions
            is GraphQLEnumType -> element.extensionDefinitions
            is GraphQLUnionType -> element.extensionDefinitions
            else -> null
        }

    @Suppress("UNCHECKED_CAST")
    private fun getScopesFromDirective(
        elementOrNode: Any,
        directiveName: String
    ): Set<String> {
        val astNode =
            when (elementOrNode) {
                // AST Node
                is DirectivesContainer<*> -> {
                    elementOrNode
                }
                // Concrete type
                is GraphQLDirectiveContainer -> {
                    elementOrNode.definition as DirectivesContainer<*>
                }
                else -> {
                    throw RuntimeException("Cannot get scopes from directive from node: $elementOrNode")
                }
            }

        val scopesDirectives =
            astNode
                .getDirectives(directiveName)
        if (scopesDirectives.size == 0) {
            throw SchemaScopeValidationError(
                "No scope directives found from node: '$astNode'",
                astNode
            )
        }

        if (scopesDirectives.size > 1) {
            throw SchemaScopeValidationError(
                "The scopes directive should not be repeated. " +
                    "Found multiple instances on node.",
                astNode
            )
        }

        val scopesArrayValue =
            scopesDirectives
                .first()
                .getArgument(SCOPED_TO_ARG)
                .value as? ArrayValue
                ?: throw SchemaScopeValidationError(
                    "@$directiveName's '$SCOPED_TO_ARG' argument must be passed an array of strings.",
                    astNode
                )

        val allScopes =
            scopesArrayValue.values.map {
                (it as? StringValue)?.value ?: throw SchemaScopeValidationError(
                    "@$directiveName's '$SCOPED_TO_ARG' argument must be passed an array of strings. " +
                        "'$it' is not a StringValue.",
                    astNode
                )
            }

        val validScopeNamesPlusWildcard = validScopeNames + setOf(WILDCARD_SCOPE)

        // Validate the scopesge
        allScopes.forEach { scope ->
            // Ensure it's a valid scope name
            if (!validScopeNamesPlusWildcard.contains(scope)) {
                throw SchemaScopeValidationError("'$scope' is not a valid scope name.", astNode)
            }
            // Ensure it's not specified more than once
            if (allScopes.count { it == scope } > 1) {
                throw SchemaScopeValidationError(
                    "'$scope' is specified more than once. Specify scopes only once when listing them in the " +
                        "'$SCOPED_TO_ARG' argument.",
                    astNode
                )
            }
        }
        if (allScopes.contains(WILDCARD_SCOPE) && allScopes.size > 1) {
            throw SchemaScopeValidationError(
                "Cannot specify specific scopes and wildcard scope ('*') together. Please specify either specific " +
                    "scopes or the wildcard scope.",
                astNode
            )
        }

        // If we're applying the wildcard scope, we should return all valid scopes
        if (allScopes.size == 1 && allScopes.first() == WILDCARD_SCOPE) {
            return validScopeNames
        }

        return allScopes.toSet()
    }
}

internal data class ElementScopeMetadata(
    val typeName: String,
    val elementsForScopes: Map<String, List<NamedNode<*>>>
) {
    fun scopesForType(): Set<String> = elementsForScopes.keys
}
