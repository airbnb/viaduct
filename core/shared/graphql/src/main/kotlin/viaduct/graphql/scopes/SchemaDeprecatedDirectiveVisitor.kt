package viaduct.graphql.scopes

import graphql.introspection.Introspection
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLUnionType
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import java.util.EnumSet
import viaduct.graphql.scopes.utils.isIntrospectionField

class SchemaDeprecatedDirectiveVisitor(
    // element.validLocations just returns the current location, so we need to pass this in
    private val validLocations: EnumSet<Introspection.DirectiveLocation>
) : TraverserVisitorStub<GraphQLSchemaElement>() {
    val invalidElements = mutableListOf<Pair<GraphQLNamedSchemaElement, String>>()

    override fun enter(context: TraverserContext<GraphQLSchemaElement>): TraversalControl {
        if (isIntrospectionField(context.thisNode())) {
            return TraversalControl.ABORT
        }
        validate(context)
        return TraversalControl.CONTINUE
    }

    /**
     * For the (applicable) children of a given node (e.g. fields, enum values, member types), filter those
     * children based on their name and the scopes that are applied to that node (both the root node and
     * its extensions).
     */
    private fun validate(context: TraverserContext<GraphQLSchemaElement>) {
        val element = context.thisNode()

        if (element !is GraphQLDirective || !element.name.equals("deprecated")) {
            return
        }

        when (context.parentNode) {
            is GraphQLScalarType -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.SCALAR)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.SCALAR}"
                        )
                    )
                }
            }
            is GraphQLObjectType -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.OBJECT)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.OBJECT}"
                        )
                    )
                }
            }
            is GraphQLFieldDefinition -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.FIELD_DEFINITION)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.FIELD_DEFINITION}"
                        )
                    )
                }
            }
            is GraphQLArgument -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.ARGUMENT_DEFINITION)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.ARGUMENT_DEFINITION}"
                        )
                    )
                }
            }
            is GraphQLInterfaceType -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.INTERFACE)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.INTERFACE}"
                        )
                    )
                }
            }
            is GraphQLUnionType -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.UNION)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.UNION}"
                        )
                    )
                }
            }
            is GraphQLEnumType -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.ENUM)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.ENUM}"
                        )
                    )
                }
            }
            is GraphQLEnumValueDefinition -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.ENUM_VALUE)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.ENUM_VALUE}"
                        )
                    )
                }
            }
            is GraphQLInputObjectType -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.INPUT_OBJECT)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed on ${Introspection.DirectiveLocation.INPUT_OBJECT}"
                        )
                    )
                }
            }
            is GraphQLInputObjectField -> {
                if (!validLocations.contains(Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION)) {
                    invalidElements.add(
                        Pair(
                            context.parentNode as GraphQLNamedSchemaElement,
                            "@${element.name} is not allowed" +
                                " on ${Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION}"
                        )
                    )
                }
            }
        }
    }
}
