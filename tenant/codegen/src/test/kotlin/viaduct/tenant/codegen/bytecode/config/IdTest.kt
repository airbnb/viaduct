package viaduct.tenant.codegen.bytecode.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.tenant.codegen.bytecode.util.assertKotlinTypeString
import viaduct.tenant.codegen.bytecode.util.field
import viaduct.tenant.codegen.bytecode.util.typedef

class IdTest {
    val extraSDL =
        """
            directive @idOf(type: String!) on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION
            interface Node { id: ID! }
        """.trimIndent()

    fun myWithSchema(
        sdl: String = "",
        block: WithSchema.() -> Unit
    ) = WithSchema("pkg", extraSDL + sdl).block()

    fun WithSchema.GlobalID(typeName: String) = "${baseTypeMapper.getGlobalIdType()}<$pkgName.$typeName>"

    fun WithSchema.GlobalOutID(typeName: String) = "${baseTypeMapper.getGlobalIdType()}<out $pkgName.$typeName>"

    fun WithSchema.check(
        typeName: String,
        fieldName: String,
        expectedReg: String,
        expectedIn: String? = null,
        idOf: String? = null,
    ) {
        val grtTypeName = idOf ?: typeName
        val field = schema.field(typeName, fieldName)
        field.assertKotlinTypeString(expectedReg, pkg = pkgName, baseTypeMapper = baseTypeMapper)
        field.assertKotlinTypeString(expectedIn ?: expectedReg, pkg = pkgName, isInput = true, baseTypeMapper = baseTypeMapper)
    }

    @Test
    fun `TypeDef_isID`() =
        myWithSchema(
            """
            scalar ID
            scalar S
            """.trimIndent()
        ) {
            assertTrue(schema.typedef("ID").isID)
            assertFalse(schema.typedef("S").isID)
        }

    @Test
    fun `modern behavior in OSS`() =
        myWithSchema(
            """
            type MyNode implements Node { id: ID! }
            type Object {
                f1: ID
                f2: ID!
                f3: [ID]
                f4: [ID!]!
            }
            """.trimIndent()
        ) {
            // In modern OSS builds, Node.id uses GlobalID type - interfaces use OUT, objects use INVARIANT
            check("Node", "id", GlobalID("Node"), GlobalOutID("Node"))
            check("MyNode", "id", GlobalID("MyNode"))

            // Regular ID fields are still String
            check("Object", "f1", "kotlin.String?")
            check("Object", "f2", "kotlin.String")
            check("Object", "f3", "kotlin.collections.List<kotlin.String?>?")
            check("Object", "f4", "kotlin.collections.List<kotlin.String>")
        }

    @Test
    fun `modern -- bare`() =
        myWithSchema {
            schema.typedef("ID").assertKotlinTypeString("kotlin.String?", baseTypeMapper = baseTypeMapper)
        }

    @Test
    fun `modern -- Node`() =
        myWithSchema {
            check("Node", "id", GlobalID("Node"), GlobalOutID("Node"))
        }

    @Test
    fun `modern -- node interface`() =
        myWithSchema(
            """
            interface MyNode implements Node {
                id: ID!
                id2: ID!
                id3: ID! @idOf(type: "MyNode")
            }
            """.trimIndent()
        ) {
            // for interfaces that are-or-implement Node, IDs are untyped unless they use @idOf
            check("MyNode", "id", GlobalID("MyNode"), GlobalOutID("MyNode"))
            check("MyNode", "id2", "kotlin.String")
            check("MyNode", "id3", GlobalID("MyNode"), GlobalOutID("MyNode"))
        }

    @Test
    fun `modern -- node object`() =
        myWithSchema(
            """
                type MyNode implements Node {
                    id: ID!
                    id2: ID!
                    id3: ID! @idOf(type:"MyNode")
                }
            """.trimIndent()
        ) {
            check("MyNode", "id", GlobalID("MyNode"))
            check("MyNode", "id2", "kotlin.String")
            check("MyNode", "id3", GlobalID("MyNode"))
        }

    @Test
    fun `modern -- nulls and lists`() =
        myWithSchema(
            """
                type MyNode implements Node { id: ID! }
                interface MyInterface implements Node { id: ID! }

                type Object {
                    f1: ID
                    f2: ID!
                    f3: [ID]
                    f4: [ID!]!

                    f5: ID @idOf(type: "MyNode")
                    f6: ID! @idOf(type: "MyNode")
                    f7: [ID] @idOf(type: "MyNode")
                    f8: [ID!]! @idOf(type: "MyNode")

                    f9: ID @idOf(type: "MyInterface")
                    fA: ID! @idOf(type: "MyInterface")
                    fB: [ID] @idOf(type: "MyInterface")
                }
            """.trimIndent()
        ) {
            check("Object", "f1", "kotlin.String?")
            check("Object", "f2", "kotlin.String")
            check("Object", "f3", "kotlin.collections.List<kotlin.String?>?")
            check("Object", "f4", "kotlin.collections.List<kotlin.String>")

            check("Object", "f5", "${GlobalID("MyNode")}?", idOf = "MyNode")
            check("Object", "f6", "${GlobalID("MyNode")}", idOf = "MyNode")
            check("Object", "f7", "kotlin.collections.List<${GlobalID("MyNode")}?>?", "kotlin.collections.List<out ${GlobalID("MyNode")}?>?", idOf = "MyNode")
            check("Object", "f8", "kotlin.collections.List<${GlobalID("MyNode")}>", "kotlin.collections.List<out ${GlobalID("MyNode")}>", idOf = "MyNode")

            check("Object", "f9", "${GlobalID("MyInterface")}?", "${GlobalOutID("MyInterface")}?", idOf = "MyInterface")
            check("Object", "fA", "${GlobalID("MyInterface")}", "${GlobalOutID("MyInterface")}", idOf = "MyInterface")
            check(
                "Object",
                "fB",
                "kotlin.collections.List<${GlobalID("MyInterface")}?>?",
                "kotlin.collections.List<out ${GlobalOutID("MyInterface")}?>?",
                idOf = "MyInterface"
            )
        }

    @Test
    fun `modern -- input`() =
        myWithSchema(
            """
                type MyNode implements Node { id: ID! }
                input Input {
                    f1: ID!
                    f2: ID! @idOf(type: "MyNode")
                    f3: ID! = "",
                    f4: ID! = "" @idOf(type: "MyNode")
                }
            """.trimIndent()
        ) {
            schema.field("Input", "f1").assertKotlinTypeString("kotlin.String", baseTypeMapper = baseTypeMapper)
            schema.field("Input", "f2").assertKotlinTypeString(GlobalID("MyNode"), baseTypeMapper = baseTypeMapper)
            schema.field("Input", "f3").assertKotlinTypeString("kotlin.String", baseTypeMapper = baseTypeMapper)
            schema.field("Input", "f4").assertKotlinTypeString(GlobalID("MyNode"), baseTypeMapper = baseTypeMapper)
        }

    @Test
    fun `modern -- field arguments`() =
        myWithSchema(
            """
                type MyNode implements Node { id: ID! }
                type Object {
                    f(
                        a1: ID!,
                        a2: ID! @idOf(type: "MyNode"),
                        a3: ID! = "",
                        a4: ID! = "" @idOf(type: "MyNode"),
                    ): Int
                }
            """.trimIndent()
        ) {
            val args = schema.field("Object", "f").args.associateBy { it.name }

            args["a1"]!!.assertKotlinTypeString("kotlin.String", baseTypeMapper = baseTypeMapper)
            args["a2"]!!.assertKotlinTypeString(GlobalID("MyNode"), baseTypeMapper = baseTypeMapper)
            args["a3"]!!.assertKotlinTypeString("kotlin.String", baseTypeMapper = baseTypeMapper)
            args["a4"]!!.assertKotlinTypeString(GlobalID("MyNode"), baseTypeMapper = baseTypeMapper)
        }

    @Test
    fun `modern -- grtNameForIdParam`() =
        myWithSchema(
            """
            interface Node2 implements Node {
                id: ID!
                id2: ID!
                id3: ID! @idOf(type: "MyNode")
            }
            """.trimIndent()
        ) {
            // for interfaces that are-or-implement Node, IDs are untyped unless they use @idOf
            assertEquals("Node2", schema.field("Node2", "id").grtNameForIdParam())
            assertEquals(null, schema.field("Node2", "id2").grtNameForIdParam())
            assertEquals("MyNode", schema.field("Node2", "id3").grtNameForIdParam())
        }
}
