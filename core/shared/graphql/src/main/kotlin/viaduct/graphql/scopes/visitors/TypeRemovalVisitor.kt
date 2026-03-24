package viaduct.graphql.scopes.visitors

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeUtil.unwrapAll
import graphql.schema.GraphQLUnionType
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import viaduct.graphql.scopes.utils.canHaveScopeApplied
import viaduct.graphql.scopes.utils.isIntrospectionField

internal class TypeRemovalVisitor(
    private val typesToRemove: MutableSet<String>,
    private val elementChildren: MutableMap<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>
) : TraverserVisitorStub<GraphQLSchemaElement>() {
    override fun enter(context: TraverserContext<GraphQLSchemaElement>): TraversalControl {
        if (isIntrospectionField(context.thisNode())) {
            return TraversalControl.ABORT
        }
        if (!canHaveScopeApplied(context.thisNode())) {
            return TraversalControl.CONTINUE
        }
        maybeRemove(context)
        return TraversalControl.CONTINUE
    }

    override fun leave(context: TraverserContext<GraphQLSchemaElement>): TraversalControl {
        if (isIntrospectionField(context.thisNode())) {
            return TraversalControl.ABORT
        }
        if (!canHaveScopeApplied(context.thisNode())) {
            return TraversalControl.CONTINUE
        }
        // We run `maybeRemove` again when we "leave" the element, so as to capture any modifications
        // further down the tree
        maybeRemove(context)
        return super.leave(context)
    }

    private fun maybeRemove(context: TraverserContext<GraphQLSchemaElement>) {
        val element = context.thisNode()
        // previous conditions ensure that we're always manipulating a named schema element
        if (element !is GraphQLNamedSchemaElement) {
            return
        }
        if (typesToRemove.contains(element.name)) {
            return
        }
        val shouldRemoveElement =
            when (element) {
                is GraphQLObjectType ->
                    elementChildren[element]?.all {
                        val namedType =
                            if (it is GraphQLInterfaceType) {
                                it
                            } else if (it is GraphQLFieldDefinition) {
                                unwrapAll(it.type)
                            } else {
                                error("Unexpected type.")
                            }
                        return@all typesToRemove.contains(namedType.name) || namedType.name == element.name
                    }
                is GraphQLInputObjectType ->
                    elementChildren[element]?.all {
                        it as GraphQLInputObjectField // assert that it's a input object field
                        val namedType = unwrapAll(it.type)
                        return@all typesToRemove.contains(namedType.name) || namedType.name == element.name
                    }
                is GraphQLInterfaceType ->
                    elementChildren[element]?.all {
                        val namedType =
                            if (it is GraphQLInterfaceType) {
                                it
                            } else if (it is GraphQLFieldDefinition) {
                                unwrapAll(it.type)
                            } else {
                                error("Unexpected type.")
                            }
                        return@all typesToRemove.contains(namedType.name) || namedType.name == element.name
                    }
                is GraphQLEnumType -> elementChildren[element]?.isEmpty()
                is GraphQLUnionType -> elementChildren[element]?.none { !typesToRemove.contains(it.name) }
                else -> false
            }

        if (shouldRemoveElement != false) {
            typesToRemove.add(element.name)
        }
    }
}
