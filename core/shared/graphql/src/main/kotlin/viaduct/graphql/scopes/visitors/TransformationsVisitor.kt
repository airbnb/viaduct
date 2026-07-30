package viaduct.graphql.scopes.visitors

import graphql.introspection.Introspection
import graphql.language.DirectivesContainer
import graphql.language.FieldDefinition
import graphql.language.NamedNode
import graphql.language.Type
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
                        val fields = newChildren.filterIsInstance<GraphQLFieldDefinition>()
                        val interfaces = newChildren.filterIsInstance<GraphQLInterfaceType>()
                        it.replaceFields(fields)
                        it.replaceInterfaces(interfaces)

                        val astChildren =
                            reconcileImplementingTypeChildren(
                                definitionFields = element.definition?.fieldDefinitions.orEmpty(),
                                extensionFields = element.extensionDefinitions.map { it.fieldDefinitions },
                                definitionInterfaces = element.definition?.implements.orEmpty(),
                                extensionInterfaces = element.extensionDefinitions.map { it.implements },
                                fields = fields,
                                interfaces = interfaces
                            )
                        val newObjectTypeDefinition = element.definition?.transform {
                            it.implementz(astChildren.interfaces.base)
                            it.fieldDefinitions(astChildren.fields.base)
                        }
                        it.definition(newObjectTypeDefinition)
                        it.extensionDefinitions(
                            astChildren.transformExtensions(
                                element.extensionDefinitions
                            ) { extension, retainedFields, retainedInterfaces ->
                                extension.transformExtension {
                                    it.fieldDefinitions(retainedFields)
                                    it.implementz(retainedInterfaces)
                                }
                            }
                        )
                    }
                is GraphQLInterfaceType ->
                    element.transform {
                        val fields = newChildren.filterIsInstance<GraphQLFieldDefinition>()
                        val interfaces = newChildren.filterIsInstance<GraphQLInterfaceType>()
                        it.replaceFields(fields)
                        it.replaceInterfaces(interfaces)

                        val astChildren =
                            reconcileImplementingTypeChildren(
                                definitionFields = element.definition?.fieldDefinitions.orEmpty(),
                                extensionFields = element.extensionDefinitions.map { it.fieldDefinitions },
                                definitionInterfaces = element.definition?.implements.orEmpty(),
                                extensionInterfaces = element.extensionDefinitions.map { it.implements },
                                fields = fields,
                                interfaces = interfaces
                            )
                        val newInterfaceDefinition = element.definition?.transform {
                            it.implementz(astChildren.interfaces.base)
                            it.definitions(astChildren.fields.base)
                        }
                        it.definition(newInterfaceDefinition)
                        it.extensionDefinitions(
                            astChildren.transformExtensions(
                                element.extensionDefinitions
                            ) { extension, retainedFields, retainedInterfaces ->
                                extension.transformExtension {
                                    it.definitions(retainedFields)
                                    it.implementz(retainedInterfaces)
                                }
                            }
                        )
                    }
                is GraphQLInputObjectType ->
                    element.transform {
                        val fields =
                            newChildren as? List<GraphQLInputObjectField>
                                ?: throw RuntimeException(
                                    "Filtered children for type ${element.name} was not a list " +
                                        "of GraphQLInputObjectField types."
                                )
                        it.replaceFields(fields)

                        val astFields =
                            reconcileDefinitions(
                                baseDefinitions = element.definition?.inputValueDefinitions.orEmpty(),
                                extensionDefinitions = element.extensionDefinitions.map { it.inputValueDefinitions },
                                retainedChildren = fields,
                                runtimeDefinition = GraphQLInputObjectField::getDefinition
                            )
                        val newInputObjectTypeDefinition = element.definition?.transform {
                            it.inputValueDefinitions(astFields.base)
                        }
                        it.definition(newInputObjectTypeDefinition)
                        it.extensionDefinitions(
                            astFields.transformExtensions(element.extensionDefinitions) { extension, retainedFields ->
                                extension.transformExtension {
                                    it.inputValueDefinitions(retainedFields)
                                }
                            }
                        )
                    }
                is GraphQLEnumType ->
                    element.transform {
                        val values =
                            newChildren as? List<GraphQLEnumValueDefinition>
                                ?: throw RuntimeException(
                                    "Filtered children for type ${element.name} was not a list " +
                                        "of GraphQLEnumValueDefinition types."
                                )
                        it.replaceValues(values)

                        val astValues =
                            reconcileDefinitions(
                                baseDefinitions = element.definition?.enumValueDefinitions.orEmpty(),
                                extensionDefinitions = element.extensionDefinitions.map { it.enumValueDefinitions },
                                retainedChildren = values,
                                runtimeDefinition = GraphQLEnumValueDefinition::getDefinition
                            )
                        val newEnumTypeDefinition = element.definition?.transform {
                            it.enumValueDefinitions(astValues.base)
                        }
                        it.definition(newEnumTypeDefinition)
                        it.extensionDefinitions(
                            astValues.transformExtensions(element.extensionDefinitions) { extension, retainedValues ->
                                extension.transformExtension {
                                    it.enumValueDefinitions(retainedValues)
                                }
                            }
                        )
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

                        val possibleTypeNames = newPossibleTypes.mapTo(mutableSetOf()) { it.name }
                        val astMembers =
                            reconcileTypeReferences(
                                baseReferences = element.definition?.memberTypes.orEmpty(),
                                extensionReferences = element.extensionDefinitions.map { it.memberTypes },
                                retainedNames = possibleTypeNames
                            )
                        val newUnionTypeDefinition = element.definition?.transform {
                            it.memberTypes(astMembers.base)
                        }
                        it.definition(newUnionTypeDefinition)
                        it.extensionDefinitions(
                            astMembers.transformExtensions(element.extensionDefinitions) { extension, retainedMembers ->
                                extension.transformExtension {
                                    it.memberTypes(retainedMembers)
                                }
                            }
                        )
                    }
                else -> null
            }

        if (transformedElement != null) {
            TreeTransformerUtil.changeNode(context, transformedElement)
        }
    }

    private data class AstChildren<AST>(
        val base: List<AST>,
        val extensions: List<List<AST>>
    )

    private data class ImplementingTypeAstChildren(
        val fields: AstChildren<FieldDefinition>,
        val interfaces: AstChildren<Type<*>>
    )

    private inline fun <AST, Extension : DirectivesContainer<*>> AstChildren<AST>.transformExtensions(
        extensionDefinitions: List<Extension>,
        transform: (Extension, List<AST>) -> Extension
    ): List<Extension> =
        extensionDefinitions.mapIndexedNotNull { index, extension ->
            extensions[index]
                .takeUnless { it.isEmpty() && extension.directives.isEmpty() }
                ?.let { transform(extension, it) }
        }

    private inline fun <Extension : DirectivesContainer<*>> ImplementingTypeAstChildren.transformExtensions(
        extensionDefinitions: List<Extension>,
        transform: (Extension, List<FieldDefinition>, List<Type<*>>) -> Extension
    ): List<Extension> =
        extensionDefinitions.mapIndexedNotNull { index, extension ->
            if (fields.extensions[index].isEmpty() &&
                interfaces.extensions[index].isEmpty() &&
                extension.directives.isEmpty()
            ) {
                null
            } else {
                transform(extension, fields.extensions[index], interfaces.extensions[index])
            }
        }

    private fun reconcileImplementingTypeChildren(
        definitionFields: List<FieldDefinition>,
        extensionFields: List<List<FieldDefinition>>,
        definitionInterfaces: List<Type<*>>,
        extensionInterfaces: List<List<Type<*>>>,
        fields: List<GraphQLFieldDefinition>,
        interfaces: List<GraphQLInterfaceType>
    ): ImplementingTypeAstChildren =
        ImplementingTypeAstChildren(
            fields =
                reconcileDefinitions(
                    definitionFields,
                    extensionFields,
                    fields,
                    GraphQLFieldDefinition::getDefinition
                ),
            interfaces =
                reconcileTypeReferences(
                    definitionInterfaces,
                    extensionInterfaces,
                    interfaces.mapTo(mutableSetOf()) { it.name }
                )
        )

    private fun <AST : NamedNode<*>, Runtime : GraphQLNamedSchemaElement> reconcileDefinitions(
        baseDefinitions: List<AST>,
        extensionDefinitions: List<List<AST>>,
        retainedChildren: List<Runtime>,
        runtimeDefinition: (Runtime) -> AST?
    ): AstChildren<AST> {
        val retainedChildrenByName = retainedChildren.associateBy { it.name }
        val originalNames =
            (baseDefinitions + extensionDefinitions.flatten())
                .mapTo(mutableSetOf()) { it.name }

        return AstChildren(
            base =
                retainedDefinitions(baseDefinitions, retainedChildrenByName, runtimeDefinition) +
                    generatedDefinitions(retainedChildren, originalNames, runtimeDefinition),
            extensions =
                extensionDefinitions.map {
                    retainedDefinitions(it, retainedChildrenByName, runtimeDefinition)
                }
        )
    }

    private fun reconcileTypeReferences(
        baseReferences: List<Type<*>>,
        extensionReferences: List<List<Type<*>>>,
        retainedNames: Set<String>
    ): AstChildren<Type<*>> {
        val originalNames = (baseReferences + extensionReferences.flatten()).typeNames()
        return AstChildren(
            base =
                baseReferences.retaining(retainedNames) +
                    generatedTypeNames(retainedNames, originalNames),
            extensions = extensionReferences.map { it.retaining(retainedNames) }
        )
    }

    private fun <AST : NamedNode<*>, Runtime : GraphQLNamedSchemaElement> retainedDefinitions(
        originalDefinitions: List<AST>,
        retainedChildrenByName: Map<String, Runtime>,
        runtimeDefinition: (Runtime) -> AST?
    ): List<AST> =
        originalDefinitions.mapNotNull { originalDefinition ->
            retainedChildrenByName[originalDefinition.name]?.let {
                runtimeDefinition(it) ?: originalDefinition
            }
        }

    private fun <AST : NamedNode<*>, Runtime : GraphQLNamedSchemaElement> generatedDefinitions(
        retainedChildren: List<Runtime>,
        originalNames: Set<String>,
        runtimeDefinition: (Runtime) -> AST?
    ): List<AST> =
        retainedChildren
            .filter { it.name !in originalNames }
            .mapNotNull(runtimeDefinition)

    private fun List<Type<*>>.retaining(retainedNames: Set<String>): List<Type<*>> = filter { (it as? TypeName)?.name in retainedNames }

    private fun List<Type<*>>.typeNames(): Set<String> = mapNotNullTo(mutableSetOf()) { (it as? TypeName)?.name }

    private fun generatedTypeNames(
        retainedNames: Set<String>,
        originalNames: Set<String>
    ): List<TypeName> = (retainedNames - originalNames).map { TypeName.newTypeName(it).build() }

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
