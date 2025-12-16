package viaduct.graphql.scopes.visitors

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLUnionType
import graphql.schema.SchemaElementChildrenContainer
import graphql.schema.SchemaTransformer
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser
import viaduct.graphql.scopes.utils.getChildrenForElement

class TypeRemovalVisitorTest {
    @Test
    fun `prunes empty object types`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: Foo
              two: Bar
              three: Baz
            }

            type Foo {
              one: String
            }

            type Bar {
              one: String
            }

            type Baz {
              # will be removed, but here so we can create the schema
              _: String
            }
        """
            )

        val transformedSchema = pruneSchema(schema)

        transformedSchema
            .getObjectType("RootQuery")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema.getObjectType("Baz").shouldBeNull()
    }

    @Test
    fun `prunes empty input object types`() {
        val schema =
            toSchema(
                """
            schema {
                query: RootQuery
            }

            type RootQuery {
                one(foo: Foo): Int
                two(bar: Bar): String
            }

            input Foo {
                one: String
            }

            input Bar {
                one: String
                baz: Baz
            }

            input Baz {
                # will be removed, but here so we can create the schema
                _: String
            }
        """
            )

        val transformedSchema = pruneSchema(schema)

        transformedSchema
            .getObjectType("RootQuery")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        (transformedSchema.getType("Bar") as? GraphQLInputObjectType)
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one")

        transformedSchema.getObjectType("Baz").shouldBeNull()
    }

    @Test
    fun `prunes transitive empty object types`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: Foo
              two: Bar
              three: Baz!
            }

            type Foo {
              one: String
            }

            type Bar {
              one: String
            }

            type Baz {
              one: Bim
            }

            type Bim {
              one: Boop!
              two: Bim
            }

            type Boop {
              # will be removed, but here so we can create the schema
              _: String
            }
        """
            )

        val transformedSchema = pruneSchema(schema)

        transformedSchema
            .getObjectType("RootQuery")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("one", "two")

        transformedSchema.getObjectType("Baz").shouldBeNull()
        transformedSchema.getObjectType("Bim").shouldBeNull()
        transformedSchema.getType("Boop").shouldBeNull()
    }

    @Test
    fun `prunes empty interface types`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              a: Foo
              b: Bar
            }

            interface OneInterface {
              one: String
            }

            interface TwoInterface {
              # will be removed, but here so we can create the schema
              _: String
            }

            type Foo implements OneInterface {
              one: String
            }

            type Bar implements OneInterface & TwoInterface {
              _: String
              one: String
              two: String
            }
        """
            )

        val transformedSchema = pruneSchema(schema)

        transformedSchema
            .getObjectType("RootQuery")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("a", "b")

        transformedSchema.getObjectType("Baz").shouldBeNull()
        transformedSchema.getObjectType("Bim").shouldBeNull()
        transformedSchema.getType("Boop").shouldBeNull()

        transformedSchema
            .getObjectType("Foo")
            ?.interfaces
            ?.map { it.name }
            .shouldContainExactly("OneInterface")

        transformedSchema
            .getObjectType("Bar")
            ?.interfaces
            ?.map { it.name }
            .shouldContainExactly("OneInterface")

        transformedSchema.getType("TwoInterface").shouldBeNull()
    }

    @Test
    fun `prunes union types with placeholder`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: UnionOne
              two: UnionTwo
            }

            # will be removed completely
            union UnionOne = Baz

            # will be filtered to Foo | Bar
            union UnionTwo = Foo | Bar | Baz

            type Foo {
              one: String
            }

            type Bar {
              one: String
            }

            type Baz {
              # will be removed, but here so we can create the schema
              _: String
            }
        """
            )

        val transformedSchema = pruneSchema(schema)

        transformedSchema
            .getObjectType("RootQuery")
            ?.fieldDefinitions
            ?.map { it.name }
            .shouldContainExactly("two")

        transformedSchema.getType("UnionOne").shouldBeNull()

        (transformedSchema.getType("UnionTwo") as? GraphQLUnionType)
            ?.types
            ?.map { it.name }
            .shouldContainExactly("Foo", "Bar")

        transformedSchema.getType("Baz").shouldBeNull()
    }

    private fun pruneSchema(schema: GraphQLSchema): GraphQLSchema {
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
                        val children = element.childrenWithTypeReferences
                        val fieldDefinitions =
                            children
                                .getChildren<GraphQLNamedSchemaElement>("fieldDefinitions")
                        val newFieldDefinitions = fieldDefinitions.filter { it.name != "_" }
                        if (newFieldDefinitions.isEmpty() && newFieldDefinitions.size != fieldDefinitions.size) {
                            val newElement =
                                element.withNewChildren(
                                    SchemaElementChildrenContainer.newSchemaElementChildrenContainer().build()
                                )
                            elementChildren[newElement] = getChildrenForElement(newElement)
                            elementChildren.remove(element)
                            context.changeNode(
                                newElement
                            )
                        }
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
