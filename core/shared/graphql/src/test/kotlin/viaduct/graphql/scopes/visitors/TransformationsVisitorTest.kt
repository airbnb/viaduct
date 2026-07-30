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
import graphql.schema.idl.SchemaPrinter
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `preserves extension ownership and directives for every supported type kind`() {
        val schema =
            toSchema(
                """
                directive @metadata(label: String!) repeatable on OBJECT | INTERFACE | INPUT_OBJECT | ENUM | UNION

                schema {
                  query: RootQuery
                }

                type RootQuery {
                  value: ExtendedObject
                }

                interface ObjectContract {
                  extensionObject: String
                }

                type ExtendedObject {
                  baseObject: String
                }

                extend type ExtendedObject implements ObjectContract @metadata(label: "object") {
                  extensionObject: String
                  removeObject: String
                }

                interface ParentInterface {
                  parent: String
                }

                interface ExtendedInterface {
                  baseInterface: String
                }

                extend interface ExtendedInterface implements ParentInterface @metadata(label: "interface") {
                  parent: String
                  extensionInterface: String
                  removeInterface: String
                }

                input ExtendedInput {
                  baseInput: String
                }

                extend input ExtendedInput @metadata(label: "input") {
                  extensionInput: String
                  removeInput: String
                }

                enum ExtendedEnum {
                  BASE_ENUM
                }

                extend enum ExtendedEnum @metadata(label: "enum") {
                  EXTENSION_ENUM
                  REMOVE_ENUM
                }

                type BaseMember {
                  value: String
                }

                type ExtensionMember {
                  value: String
                }

                type RemoveMember {
                  value: String
                }

                union ExtendedUnion = BaseMember

                extend union ExtendedUnion @metadata(label: "union") = ExtensionMember | RemoveMember
                """.trimIndent()
            )

        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            when (element.name) {
                "ExtendedObject" -> currentChildren.filterNot { it.name == "removeObject" }
                "ExtendedInterface" -> currentChildren.filterNot { it.name == "removeInterface" }
                "ExtendedInput" -> currentChildren.filterNot { it.name == "removeInput" }
                "ExtendedEnum" -> currentChildren.filterNot { it.name == "REMOVE_ENUM" }
                "ExtendedUnion" -> currentChildren.filterNot { it.name == "RemoveMember" }
                else -> currentChildren
            }
        }

        val objectType = transformedSchema.getObjectType("ExtendedObject")
        assertEquals(listOf("baseObject"), objectType.definition!!.fieldDefinitions.map { it.name })
        assertEquals(
            listOf("extensionObject"),
            objectType.extensionDefinitions.single().fieldDefinitions.map { it.name }
        )
        assertEquals(
            listOf("ObjectContract"),
            objectType.extensionDefinitions.single().implements.map { (it as TypeName).name }
        )
        assertEquals(listOf("metadata"), objectType.extensionDefinitions.single().directives.map { it.name })

        val interfaceType = transformedSchema.getTypeAs<GraphQLInterfaceType>("ExtendedInterface")
        assertEquals(listOf("baseInterface"), interfaceType.definition!!.fieldDefinitions.map { it.name })
        assertEquals(
            listOf("parent", "extensionInterface"),
            interfaceType.extensionDefinitions.single().fieldDefinitions.map { it.name }
        )
        assertEquals(
            listOf("ParentInterface"),
            interfaceType.extensionDefinitions.single().implements.map { (it as TypeName).name }
        )
        assertEquals(listOf("metadata"), interfaceType.extensionDefinitions.single().directives.map { it.name })

        val inputType = transformedSchema.getTypeAs<GraphQLInputObjectType>("ExtendedInput")
        assertEquals(listOf("baseInput"), inputType.definition!!.inputValueDefinitions.map { it.name })
        assertEquals(
            listOf("extensionInput"),
            inputType.extensionDefinitions.single().inputValueDefinitions.map { it.name }
        )
        assertEquals(listOf("metadata"), inputType.extensionDefinitions.single().directives.map { it.name })

        val enumType = transformedSchema.getTypeAs<GraphQLEnumType>("ExtendedEnum")
        assertEquals(listOf("BASE_ENUM"), enumType.definition!!.enumValueDefinitions.map { it.name })
        assertEquals(
            listOf("EXTENSION_ENUM"),
            enumType.extensionDefinitions.single().enumValueDefinitions.map { it.name }
        )
        assertEquals(listOf("metadata"), enumType.extensionDefinitions.single().directives.map { it.name })

        val unionType = transformedSchema.getTypeAs<GraphQLUnionType>("ExtendedUnion")
        assertEquals(listOf("BaseMember"), unionType.definition!!.memberTypes.map { (it as TypeName).name })
        assertEquals(
            listOf("ExtensionMember"),
            unionType.extensionDefinitions.single().memberTypes.map { (it as TypeName).name }
        )
        assertEquals(listOf("metadata"), unionType.extensionDefinitions.single().directives.map { it.name })

        val roundTrippedSchema = toSchema(printAstSchema(transformedSchema))
        assertEquals(
            listOf("baseObject", "extensionObject"),
            roundTrippedSchema.getObjectType("ExtendedObject").fieldDefinitions.map { it.name }
        )
        assertNull(roundTrippedSchema.getObjectType("ExtendedObject").getFieldDefinition("removeObject"))
        assertTrue(roundTrippedSchema.getObjectType("ExtendedObject").hasAppliedDirective("metadata"))
        assertEquals(
            listOf("baseInterface", "parent", "extensionInterface"),
            roundTrippedSchema.getTypeAs<GraphQLInterfaceType>("ExtendedInterface").fieldDefinitions.map { it.name }
        )
        assertTrue(roundTrippedSchema.getTypeAs<GraphQLInterfaceType>("ExtendedInterface").hasAppliedDirective("metadata"))
        assertEquals(
            listOf("baseInput", "extensionInput"),
            roundTrippedSchema.getTypeAs<GraphQLInputObjectType>("ExtendedInput").fieldDefinitions.map { it.name }
        )
        assertTrue(roundTrippedSchema.getTypeAs<GraphQLInputObjectType>("ExtendedInput").hasAppliedDirective("metadata"))
        assertEquals(
            listOf("BASE_ENUM", "EXTENSION_ENUM"),
            roundTrippedSchema.getTypeAs<GraphQLEnumType>("ExtendedEnum").values.map { it.name }
        )
        assertTrue(roundTrippedSchema.getTypeAs<GraphQLEnumType>("ExtendedEnum").hasAppliedDirective("metadata"))
        assertEquals(
            listOf("BaseMember", "ExtensionMember"),
            roundTrippedSchema.getTypeAs<GraphQLUnionType>("ExtendedUnion").types.map { it.name }
        )
        assertTrue(roundTrippedSchema.getTypeAs<GraphQLUnionType>("ExtendedUnion").hasAppliedDirective("metadata"))
    }

    @Test
    fun `keeps directive-only extensions and drops empty extensions`() {
        val schema =
            toSchema(
                """
                directive @metadata on OBJECT

                schema {
                  query: RootQuery
                }

                type RootQuery {
                  value: DirectiveOnlyExtension
                }

                type DirectiveOnlyExtension {
                  base: String
                }

                extend type DirectiveOnlyExtension @metadata {
                  remove: String
                }

                type EmptyExtension {
                  base: String
                }

                extend type EmptyExtension {
                  remove: String
                }
                """.trimIndent()
            )

        val transformedSchema = transformSchema(schema) { element, currentChildren ->
            if (element.name == "DirectiveOnlyExtension" || element.name == "EmptyExtension") {
                currentChildren.filterNot { it.name == "remove" }
            } else {
                currentChildren
            }
        }

        val directiveOnlyType = transformedSchema.getObjectType("DirectiveOnlyExtension")
        assertTrue(directiveOnlyType.extensionDefinitions.single().fieldDefinitions.isEmpty())
        assertEquals(
            listOf("metadata"),
            directiveOnlyType.extensionDefinitions.single().directives.map { it.name }
        )
        assertTrue(transformedSchema.getObjectType("EmptyExtension").extensionDefinitions.isEmpty())

        val roundTrippedSchema = toSchema(printAstSchema(transformedSchema))
        assertTrue(roundTrippedSchema.getObjectType("DirectiveOnlyExtension").hasAppliedDirective("metadata"))
        assertNull(roundTrippedSchema.getObjectType("DirectiveOnlyExtension").getFieldDefinition("remove"))
        assertNull(roundTrippedSchema.getObjectType("EmptyExtension").getFieldDefinition("remove"))
    }

    private fun printAstSchema(schema: GraphQLSchema): String =
        SchemaPrinter(
            SchemaPrinter.Options.defaultOptions()
                .useAstDefinitions(true)
        ).print(schema)

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
