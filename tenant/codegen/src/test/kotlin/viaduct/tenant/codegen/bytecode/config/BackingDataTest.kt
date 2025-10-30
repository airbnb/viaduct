package viaduct.tenant.codegen.bytecode.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.util.assertKotlinTypeString
import viaduct.tenant.codegen.bytecode.util.expr
import viaduct.tenant.codegen.bytecode.util.field
import viaduct.tenant.codegen.bytecode.util.typedef

class BackingDataTest {
    private fun mkSchema(sdl: String = ""): ViaductSchema =
        viaduct.graphql.schema.test.mkSchema(
            """
                scalar BackingData
                directive @backingData(class: String) on FIELD_DEFINITION
                $sdl
            """.trimIndent()
        )

    @Test
    fun `TypeDef -- isBackingDataType`() {
        val schema = mkSchema(
            """
                type Obj {
                  f1: Int
                  f2: BackingData
                }
            """.trimIndent()
        )
        assertTrue(schema.typedef("BackingData").isBackingDataType)
        assertFalse(schema.typedef("Obj").isBackingDataType)
        assertFalse(schema.expr("Obj", "f1").baseTypeDef.isBackingDataType)
        assertTrue(schema.expr("Obj", "f2").baseTypeDef.isBackingDataType)
    }

    @Test
    fun `Field -- isBackingDataType`() {
        val schema = mkSchema(
            """
                type Obj {
                  f1: Int @backingData(class: "Cls")
                  f2: BackingData
                  f3: BackingData @backingData(class: "Cls")
                }
            """.trimIndent()
        )
        assertFalse(schema.field("Obj", "f1").isBackingDataType)
        assertTrue(schema.field("Obj", "f2").isBackingDataType)
        assertTrue(schema.field("Obj", "f3").isBackingDataType)
    }

    @Test
    fun `directive -- backingData`() {
        val schema = mkSchema(
            """
                type Obj {
                  f1: BackingData @backingData(class: "Cls")
                }
            """.trimIndent()
        )
        assertEquals(BackingData("Cls"), schema.field("Obj", "f1").appliedDirectives.backingData)
    }

    @Test
    fun `Record -- codegenIncludedFields`() {
        val schema = mkSchema(
            """
                type Obj {
                  f1: BackingData
                  f2: String
                }
                interface Interface {
                  f1: BackingData
                  f2: String
                }
                input Input {
                  f1: BackingData
                  f2: String
                }
            """.trimIndent()
        )
        assertEquals(listOf(schema.field("Obj", "f2")), (schema.typedef("Obj") as ViaductSchema.Record).codegenIncludedFields)
        assertEquals(listOf(schema.field("Interface", "f2")), (schema.typedef("Interface") as ViaductSchema.Record).codegenIncludedFields)
        assertEquals(listOf(schema.field("Input", "f2")), (schema.typedef("Input") as ViaductSchema.Record).codegenIncludedFields)
    }

    @Test
    fun `kotlinTypeString`() {
        val schema = mkSchema(
            """
                type Obj {
                  f1: BackingData
                  f2: BackingData!
                  f3: [BackingData]
                  f4: [BackingData!]!
                }
            """.trimIndent()
        )

        schema.field("Obj", "f1").assertKotlinTypeString("kotlin.Any?")
        schema.field("Obj", "f2").assertKotlinTypeString("kotlin.Any")
        schema.field("Obj", "f3").assertKotlinTypeString("kotlin.collections.List<kotlin.Any?>?")
        schema.field("Obj", "f4").assertKotlinTypeString("kotlin.collections.List<kotlin.Any>")
    }
}
