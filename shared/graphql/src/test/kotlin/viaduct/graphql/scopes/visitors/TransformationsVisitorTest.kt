package viaduct.graphql.scopes.visitors

import graphql.Scalars
import graphql.language.EnumValueDefinition
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLUnionType
import graphql.schema.SchemaTransformer
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser
import viaduct.graphql.scopes.utils.getChildrenForElement

class TransformationsVisitorTest {
    @Test
    fun `test object type field`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              add: AddObjectType
              remove: RemoveObjectType
            }

            type AddObjectType {
              one: String
            }

            type RemoveObjectType {
              one: String
              two: String
            }
        """
            )
        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            val newChildren = mutableListOf<GraphQLNamedSchemaElement>()
            if (element.name == "AddObjectType") {
                val newField = GraphQLFieldDefinition
                    .newFieldDefinition()
                    .name("two")
                    .type(Scalars.GraphQLString)
                    .definition(
                        FieldDefinition
                            .newFieldDefinition()
                            .name("two")
                            .type(TypeName.newTypeName("String").build())
                            .build()
                    ).build()
                newChildren.addAll(currentChildren)
                newChildren.add(newField)
                newChildren
            } else if (element.name == "RemoveObjectType") {
                newChildren.addAll(currentChildren.filter { it.name != "two" })
                newChildren
            } else {
                currentChildren
            }
        }

        transformedSchema
            .getObjectType("AddObjectType")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema
            .getObjectType("AddObjectType")
            ?.definition
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema
            .getObjectType("RemoveObjectType")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")

        transformedSchema
            .getObjectType("RemoveObjectType")
            ?.definition
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")
    }

    @Test
    fun `test interface type field`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              unused: String
            }

            interface AddInterfaceType {
              one: String
            }

            interface RemoveInterfaceType {
              one: String
              two: String
            }
        """
            )
        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            val newChildren = mutableListOf<GraphQLNamedSchemaElement>()
            if (element.name == "AddInterfaceType") {
                val newField = GraphQLFieldDefinition
                    .newFieldDefinition()
                    .name("two")
                    .type(Scalars.GraphQLString)
                    .definition(
                        FieldDefinition
                            .newFieldDefinition()
                            .name("two")
                            .type(TypeName.newTypeName("String").build())
                            .build()
                    ).build()
                newChildren.addAll(currentChildren)
                newChildren.add(newField)
                newChildren
            } else if (element.name == "RemoveInterfaceType") {
                newChildren.addAll(currentChildren.filter { it.name != "two" })
                newChildren
            } else {
                currentChildren
            }
        }

        transformedSchema
            .getTypeAs<GraphQLInterfaceType>("AddInterfaceType")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema
            .getTypeAs<GraphQLInterfaceType>("AddInterfaceType")
            ?.definition
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema
            .getTypeAs<GraphQLInterfaceType>("RemoveInterfaceType")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")

        transformedSchema
            .getTypeAs<GraphQLInterfaceType>("RemoveInterfaceType")
            ?.definition
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")
    }

    @Test
    fun `test input type field`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              unused: String
            }

            input AddInputType {
              one: String
            }

            input RemoveInputType {
              one: String
              two: String
            }
        """
            )
        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            val newChildren = mutableListOf<GraphQLNamedSchemaElement>()
            if (element.name == "AddInputType") {
                val newField = GraphQLInputObjectField
                    .newInputObjectField()
                    .name("two")
                    .type(Scalars.GraphQLString)
                    .definition(
                        InputValueDefinition
                            .newInputValueDefinition()
                            .name("two")
                            .type(TypeName.newTypeName("String").build())
                            .build()
                    ).build()
                newChildren.addAll(currentChildren)
                newChildren.add(newField)
                newChildren
            } else if (element.name == "RemoveInputType") {
                newChildren.addAll(currentChildren.filter { it.name != "two" })
                newChildren
            } else {
                currentChildren
            }
        }

        transformedSchema
            .getTypeAs<GraphQLInputObjectType>("AddInputType")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema
            .getTypeAs<GraphQLInputObjectType>("AddInputType")
            ?.definition
            ?.inputValueDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema
            .getTypeAs<GraphQLInputObjectType>("RemoveInputType")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")

        transformedSchema
            .getTypeAs<GraphQLInputObjectType>("RemoveInputType")
            ?.definition
            ?.inputValueDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")
    }

    @Test
    fun `test enum type field`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              unused: String
            }

            enum AddEnum {
              ONE
            }

            enum RemoveEnum {
              ONE
              TWO
            }
        """
            )
        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            val newChildren = mutableListOf<GraphQLNamedSchemaElement>()
            if (element.name == "AddEnum") {
                val newField = GraphQLEnumValueDefinition
                    .newEnumValueDefinition()
                    .name("TWO")
                    .definition(EnumValueDefinition.newEnumValueDefinition().name("TWO").build())
                    .build()
                newChildren.addAll(currentChildren)
                newChildren.add(newField)
                newChildren
            } else if (element.name == "RemoveEnum") {
                newChildren.addAll(currentChildren.filter { it.name != "TWO" })
                newChildren
            } else {
                currentChildren
            }
        }

        transformedSchema
            .getTypeAs<GraphQLEnumType>("AddEnum")
            ?.values
            ?.map { it.name }
            .shouldContainExactly("ONE", "TWO")

        transformedSchema
            .getTypeAs<GraphQLEnumType>("AddEnum")
            ?.definition
            ?.enumValueDefinitions
            ?.map { it.name }
            .shouldContainExactly("ONE", "TWO")

        transformedSchema
            .getTypeAs<GraphQLEnumType>("RemoveEnum")
            ?.values
            ?.map { it.name }
            .shouldContainExactly("ONE")

        transformedSchema
            .getTypeAs<GraphQLEnumType>("RemoveEnum")
            ?.definition
            ?.enumValueDefinitions
            ?.map { it.name }
            .shouldContainExactly("ONE")
    }

    @Test
    fun `test union type field`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              unused: String
            }

            union AddUnionType = ONE

            union RemoveUnionType = ONE | TWO

            type ONE {
              one: String
            }

            type TWO {
              two: String
            }
        """
            )
        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            val newChildren = mutableListOf<GraphQLNamedSchemaElement>()
            if (element.name == "AddUnionType") {
                val newField = schema.getObjectType("TWO")
                newChildren.addAll(currentChildren)
                newChildren.add(newField)
                newChildren
            } else if (element.name == "RemoveUnionType") {
                newChildren.addAll(currentChildren.filter { it.name != "TWO" })
                newChildren
            } else {
                currentChildren
            }
        }

        transformedSchema
            .getTypeAs<GraphQLUnionType>("AddUnionType")
            ?.types
            ?.map { it.name }
            .shouldContainExactly("ONE", "TWO")

        transformedSchema
            .getTypeAs<GraphQLUnionType>("AddUnionType")
            ?.definition
            ?.memberTypes
            ?.map { it as TypeName }
            ?.map { it.name }
            .shouldContainExactly("ONE", "TWO")

        transformedSchema
            .getTypeAs<GraphQLUnionType>("RemoveUnionType")
            ?.types
            ?.map { it.name }
            .shouldContainExactly("ONE")

        transformedSchema
            .getTypeAs<GraphQLUnionType>("RemoveUnionType")
            ?.definition
            ?.memberTypes
            ?.map { it as TypeName }
            ?.map { it.name }
            .shouldContainExactly("ONE")
    }

    private fun transformSchema(
        schema: GraphQLSchema,
        getNewChildren: (GraphQLNamedSchemaElement, List<GraphQLNamedSchemaElement>) -> List<GraphQLNamedSchemaElement>
    ): GraphQLSchema {
        val typesToRemove = mutableSetOf<String>()
        val elementChildren =
            schema.allTypesAsList
                .map {
                    Pair(it as GraphQLSchemaElement, getChildrenForElement(it))
                }.toMap()
                .toMutableMap()
        buildSchemaTraverser(schema).traverse(
            StubRoot(schema),
            CompositeVisitor(
                object : TraverserVisitorStub<GraphQLSchemaElement>() {
                    override fun enter(context: TraverserContext<GraphQLSchemaElement>): TraversalControl {
                        val element = context.thisNode()
                        // previous conditions ensure that we're always manipulating a named schema element
                        if (element !is GraphQLNamedSchemaElement) {
                            return super.enter(context)
                        }
                        elementChildren[element] = getNewChildren(element, elementChildren[element] ?: emptyList())
                        return TraversalControl.CONTINUE
                    }
                },
                TypeRemovalVisitor(typesToRemove, elementChildren)
            )
        )

        return SchemaTransformer.transformSchema(
            schema,
            TransformationsVisitor(
                SchemaTransformations(
                    elementChildren = elementChildren,
                    typesNamesToRemove = typesToRemove
                )
            )
        )
    }
}
