package viaduct.graphql.scopes.visitors

import graphql.introspection.Introspection
import graphql.language.TypeName
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeUtil.unwrapAll
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.GraphQLUnionType
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.getChildrenForElement

/**
 * Given a set of transformations, transform the schema accordingly.
 *
 * In the future this class could be made more generic, but for now it only handles type
 * removals and updates of children.
 */
internal class TransformationsVisitor(
    private val transformations: SchemaTransformations
) : GraphQLTypeVisitorStub() {
    override fun visitGraphQLType(
        node: GraphQLSchemaElement,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl {
        // Skip introspection type hierarchy
        if (node == Introspection.__Schema ||
            node == Introspection.__Type ||
            node == Introspection.__TypeKind
        ) {
            return TraversalControl.ABORT
        }
        // Remove schema elements based on the "typesNamesToRemove" transformations
        maybeRemoveElement(context)
        // Modify elements based on the "newElementChildren" transformations
        maybeModifyElement(context)
        return TraversalControl.CONTINUE
    }

    private fun maybeModifyElement(context: TraverserContext<GraphQLSchemaElement>) {
        if (context.isDeleted) {
            return
        }

        val element = context.thisNode()
        // Only modify elements that can have scope directives applied
        if (!canHaveScopeApplied(element) || element !is GraphQLNamedSchemaElement) {
            return
        }
        val currentChildren = getChildrenForElement(element)
        val newChildren = transformations.elementChildren[element]
        // If we can't get the children for this element or the element is not present in the
        // transformation data, continue
        if (currentChildren == null || newChildren == null) {
            return
        }

        // We never need to modify the children, only add/remove. Therefore, we can skip modification
        // if the current and new children are the same
        if (currentChildren.size == newChildren.size &&
            currentChildren.map { it.name }.toSet() == newChildren.map { it.name }.toSet()
        ) {
            return
        }

        modifyElement(context, newChildren)
    }

    @Suppress("UNCHECKED_CAST")
    private fun modifyElement(
        context: TraverserContext<GraphQLSchemaElement>,
        newChildren: List<GraphQLSchemaElement>
    ) {
        val transformedElement =
            when (val element = context.thisNode()) {
                is GraphQLObjectType ->
                    element.transform {
                        it.replaceFields(
                            newChildren.filter { it is GraphQLFieldDefinition } as List<GraphQLFieldDefinition>
                        )
                        it.replaceInterfaces(
                            newChildren.filter { it is GraphQLInterfaceType } as List<GraphQLInterfaceType>
                        )

                        val fields = newChildren
                            .filter { it is GraphQLFieldDefinition }
                            .map { it as GraphQLFieldDefinition }
                            .map { it.definition }
                        val interfaces = newChildren
                            .filter { it is GraphQLInterfaceType }
                            .map { it as GraphQLInterfaceType }
                            .map { TypeName.newTypeName(it.name).build() }
                        val newObjectTypeDefinition = element.definition?.transform {
                            it.implementz(interfaces)
                            it.fieldDefinitions(fields)
                        }
                        it.definition(newObjectTypeDefinition)
                    }
                is GraphQLInterfaceType ->
                    element.transform {
                        it.replaceFields(
                            newChildren.filter { it is GraphQLFieldDefinition } as List<GraphQLFieldDefinition>
                        )
                        it.replaceInterfaces(
                            newChildren.filter { it is GraphQLInterfaceType } as List<GraphQLInterfaceType>
                        )

                        val fields = newChildren
                            .filter { it is GraphQLFieldDefinition }
                            .map { it as GraphQLFieldDefinition }
                            .map { it.definition }
                        val interfaces = newChildren
                            .filter { it is GraphQLInterfaceType }
                            .map { it as GraphQLInterfaceType }
                            .map { TypeName.newTypeName(it.name).build() }
                        val newInterfaceDefinition = element.definition?.transform {
                            it.implementz(interfaces)
                            it.definitions(fields)
                        }
                        it.definition(newInterfaceDefinition)
                    }
                is GraphQLInputObjectType ->
                    element.transform {
                        it.replaceFields(
                            newChildren as? List<GraphQLInputObjectField>
                                ?: throw RuntimeException(
                                    "Filtered children for type ${element.name} was not a list " +
                                        "of GraphQLInputObjectField types."
                                )
                        )

                        val fields = newChildren.map { it.definition }
                        val newInputObjectTypeDefinition = element.definition?.transform {
                            it.inputValueDefinitions(fields)
                        }
                        it.definition(newInputObjectTypeDefinition)
                    }
                is GraphQLEnumType ->
                    element.transform {
                        it.replaceValues(
                            newChildren as? List<GraphQLEnumValueDefinition>
                                ?: throw RuntimeException(
                                    "Filtered children for type ${element.name} was not a list " +
                                        "of GraphQLEnumValueDefinition types."
                                )
                        )

                        val values = newChildren.map { it.definition }
                        val newEnumTypeDefinition = element.definition?.transform {
                            it.enumValueDefinitions(values)
                        }
                        it.definition(newEnumTypeDefinition)
                    }
                is GraphQLUnionType ->
                    element.transform {
                        val newPossibleTypes =
                            newChildren as? List<GraphQLNamedOutputType>
                                ?: throw RuntimeException(
                                    "Filtered children for type ${element.name} was not a list " +
                                        "of GraphQLObjectType types."
                                )
                        it.replacePossibleTypes(newPossibleTypes as List<GraphQLObjectType>)

                        val members = newPossibleTypes.map { TypeName.newTypeName(it.name).build() }
                        val newUnionTypeDefinition = element.definition?.transform {
                            it.memberTypes(members)
                        }
                        it.definition(newUnionTypeDefinition)
                    }
                else -> null
            }

        if (transformedElement != null) {
            TreeTransformerUtil.changeNode(context, transformedElement)
        }
    }

    private fun maybeRemoveElement(context: TraverserContext<GraphQLSchemaElement>) {
        if (shouldRemoveElement(context.thisNode())) {
            TreeTransformerUtil.deleteNode(context)
        }
    }

    private fun shouldRemoveElement(element: GraphQLSchemaElement): Boolean =
        when (element) {
            is GraphQLArgument -> shouldRemoveElement(unwrapAll(element.type))
            is GraphQLFieldDefinition -> shouldRemoveElement(unwrapAll(element.type))
            is GraphQLInputObjectField -> shouldRemoveElement(unwrapAll(element.type))
            is GraphQLNamedType -> transformations.typesNamesToRemove.contains(element.name)
            else -> false
        }
}

data class SchemaTransformations(
    val elementChildren: Map<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?> = mapOf(),
    val typesNamesToRemove: Set<String> = setOf()
) {
    override fun toString(): String =
        "elementChildren = " +
            elementChildren.map { (key, value) ->
                Pair((key as GraphQLNamedSchemaElement).name, value?.map { it.name })
            } + ", typeNamesToRemove = " + typesNamesToRemove
}
