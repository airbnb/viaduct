package viaduct.graphql.scopes.utils

import graphql.introspection.Introspection
import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NamedNode
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.util.Traverser
import viaduct.graphql.utils.DefaultSchemaFactory

private const val BACKING_DATA_SCALAR_NAME = "BackingData"
private val PARENT_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.PARENT.directiveName
private val TENANT_LOCAL_DIRECTIVE_NAME = DefaultSchemaFactory.DefaultDirective.TENANT_LOCAL.directiveName

fun buildSchemaTraverser(schema: GraphQLSchema) =
    Traverser.depthFirstWithNamedChildren<GraphQLSchemaElement>(
        {
            it.childrenWithTypeReferences.children.mapValues {
                it.value.map {
                    // resolve the type reference
                    if (it is GraphQLTypeReference) {
                        schema.typeMap[it.name]
                    } else {
                        it
                    }
                }
            }
        },
        null,
        null
    )

fun isIntrospectionType(element: GraphQLSchemaElement) =
    element == Introspection.__Schema ||
        element == Introspection.__Directive ||
        element == Introspection.__DirectiveLocation ||
        element == Introspection.__EnumValue ||
        element == Introspection.__Field ||
        element == Introspection.__InputValue ||
        element == Introspection.__TypeKind ||
        element == Introspection.__Type

fun isIntrospectionField(element: GraphQLSchemaElement) =
    element == Introspection.SchemaMetaFieldDef ||
        element == Introspection.TypeMetaFieldDef ||
        element == Introspection.TypeNameMetaFieldDef

/**
 * Get fields, values, or member types for the given element
 */
internal fun getChildrenForElement(element: GraphQLSchemaElement): List<GraphQLNamedSchemaElement>? =
    when (element) {
        is GraphQLObjectType -> element.fieldDefinitions + element.interfaces
        is GraphQLInputObjectType -> element.fieldDefinitions
        is GraphQLInterfaceType -> element.fieldDefinitions + element.interfaces
        is GraphQLEnumType -> element.values
        is GraphQLUnionType -> element.types
        else -> null
    }

/**
 * Return true if the provided element can have scopes applied
 */
internal fun canHaveScopeApplied(element: GraphQLSchemaElement): Boolean =
    (
        element is StubRoot ||
            element is GraphQLObjectType ||
            element is GraphQLInputObjectType ||
            element is GraphQLInterfaceType ||
            element is GraphQLEnumType ||
            element is GraphQLUnionType
    )

internal fun isTenantLocalEquivalentField(element: GraphQLNamedSchemaElement): Boolean =
    element is GraphQLFieldDefinition &&
        (
            element.hasAppliedDirective(TENANT_LOCAL_DIRECTIVE_NAME) ||
                element.hasAppliedDirective(PARENT_DIRECTIVE_NAME) ||
                GraphQLTypeUtil.unwrapAll(element.type).name == BACKING_DATA_SCALAR_NAME
        )

internal fun isTenantLocalEquivalentFieldNode(node: NamedNode<*>): Boolean =
    node is FieldDefinition &&
        (
            node.getDirectives(TENANT_LOCAL_DIRECTIVE_NAME).isNotEmpty() ||
                node.getDirectives(PARENT_DIRECTIVE_NAME).isNotEmpty() ||
                unwrapTypeName(node.type) == BACKING_DATA_SCALAR_NAME
        )

private fun unwrapTypeName(type: Type<*>): String? =
    when (type) {
        is NonNullType -> unwrapTypeName(type.type)
        is ListType -> unwrapTypeName(type.type)
        is TypeName -> type.name
        else -> null
    }
