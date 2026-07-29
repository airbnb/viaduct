package viaduct.graphql.scopes.utils

import graphql.introspection.Introspection
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import graphql.util.Traverser

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
