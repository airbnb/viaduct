package viaduct.graphql.scopes.utils

import graphql.Assert
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeVisitor
import graphql.schema.SchemaElementChildrenContainer
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import java.util.LinkedHashSet

/**
 * Artificial schema element which serves as root element for the transformation. Enables traversing the entire schema
 * with Node-based traversal utilities. Does not include "Introspection" types.
 *
 * Forked from https://github.com/graphql-java/graphql-java/blob/bb87d8f5211f4770934bbca408ca41557d5b4196/src/main/java/graphql/schema/SchemaTransformer.java#L38
 */
class StubRoot(
    private var schema: GraphQLSchema
) : GraphQLSchemaElement {
    companion object {
        const val QUERY = "query"
        const val MUTATION = "mutation"
        const val SUBSCRIPTION = "subscription"
        const val ADD_TYPES = "addTypes"
        const val DIRECTIVES = "directives"
    }

    private var query: GraphQLObjectType = schema.queryType
    private var mutation: GraphQLObjectType? = if (schema.isSupportingMutations) schema.mutationType else null
    private var subscription: GraphQLObjectType? =
        if (schema.isSupportingSubscriptions) {
            schema.subscriptionType
        } else {
            null
        }
    private var additionalTypes: Set<GraphQLType> = schema.additionalTypes
    private var directives: Set<GraphQLDirective> = schema.directives.toSet()

    override fun getChildren(): List<GraphQLSchemaElement> = Assert.assertShouldNeverHappen()

    override fun getChildrenWithTypeReferences(): SchemaElementChildrenContainer {
        val builder =
            SchemaElementChildrenContainer
                .newSchemaElementChildrenContainer()
                .child(QUERY, query)
        if (schema.isSupportingMutations) {
            builder.child(MUTATION, mutation)
        }
        if (schema.isSupportingSubscriptions) {
            builder.child(SUBSCRIPTION, subscription)
        }
        builder.children(ADD_TYPES, additionalTypes)
        builder.children(DIRECTIVES, directives)
        return builder.build()
    }

    override fun withNewChildren(newChildren: SchemaElementChildrenContainer): GraphQLSchemaElement {
        query = newChildren.getChildOrNull(QUERY)
        mutation = newChildren.getChildOrNull(MUTATION)
        subscription = newChildren.getChildOrNull(SUBSCRIPTION)
        additionalTypes = LinkedHashSet(newChildren.getChildren(ADD_TYPES))
        directives = LinkedHashSet(newChildren.getChildren(DIRECTIVES))
        return this
    }

    override fun accept(
        context: TraverserContext<GraphQLSchemaElement>,
        visitor: GraphQLTypeVisitor
    ): TraversalControl = Assert.assertShouldNeverHappen()

    override fun copy(): GraphQLSchemaElement = this
}
