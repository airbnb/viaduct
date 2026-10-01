package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class EngineValueDataTest {
    @Test
    fun `object-or-error data retains its precise outcome`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val objectValue = engineObjectDataOf(schema.requireQueryTypeDef())
        val errorValue = EngineErrorData.of()

        assertSame(
            objectValue,
            assertIs<EngineObjectOrErrorData.Success>(
                EngineObjectOrErrorData.of(objectValue),
            ).value,
        )
        assertSame(
            errorValue,
            assertIs<EngineObjectOrErrorData.Error>(
                EngineObjectOrErrorData.of(errorValue),
            ).error,
        )
    }

    @Test
    fun `simple casts use production-compatible strings for String ID and enum`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val role = schema.requireType("Role") as ViaductSchema.Enum
        val otherRole = schema.requireType("OtherRole") as ViaductSchema.Enum
        val string = schema.requireType("String") as ViaductSchema.Scalar
        val id = schema.requireType("ID") as ViaductSchema.Scalar

        assertEquals(
            "same",
            toEngineSimpleData(ViaductSchema.TypeExpr(string), "same"),
        )
        assertEquals(
            "same",
            toEngineSimpleData(ViaductSchema.TypeExpr(id), "same"),
        )
        assertEquals(
            "ADMIN",
            toEngineSimpleData(ViaductSchema.TypeExpr(role), "ADMIN"),
        )
        assertEquals(
            "ADMIN",
            toEngineSimpleData(ViaductSchema.TypeExpr(otherRole), "ADMIN"),
        )
    }

    @Test
    fun `casts reject non-finite floats and unknown enum names`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val role = schema.requireType("Role") as ViaductSchema.Enum
        val float = schema.requireType("Float") as ViaductSchema.Scalar
        val id = schema.requireType("ID") as ViaductSchema.Scalar

        assertFailsWith<ClassCastException> {
            toEngineSimpleData(ViaductSchema.TypeExpr(float), Double.NaN)
        }
        assertFailsWith<ClassCastException> {
            toEngineSimpleData(ViaductSchema.TypeExpr(id), 1)
        }
        assertFailsWith<ClassCastException> {
            toEngineSimpleData(
                ViaductSchema.TypeExpr(role),
                "MISSING",
            )
        }
    }

    @Test
    fun `input casts recursively copy and schema-check lists and objects`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filter = schema.requireType("Filter") as ViaductSchema.Input
        val ids = mutableListOf<Any?>("first")
        val source = mutableMapOf<String, Any?>("ids" to ids, "role" to "ADMIN")

        val converted = toEngineInputObjectData(filter, source)
        source["role"] = "MEMBER"
        ids += "second"

        val convertedIds = assertIs<EngineInputListData>(converted.getValue("ids"))
        assertNotSame(ids, convertedIds)
        assertEquals(listOf("first"), convertedIds)
        assertEquals("ADMIN", converted.getValue("role"))
    }

    @Test
    fun `input casts require an actual list`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val filter = schema.requireType("Filter") as ViaductSchema.Input
        val listType = filter.requireField("ids").inputType

        assertFailsWith<ClassCastException> {
            toEngineInputData(listType, 1)
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            enum Role {
              MEMBER
              ADMIN
            }

            enum OtherRole {
              ADMIN
            }

            input Filter {
              ids: [ID!]!
              role: Role!
            }

            type Query {
              value(filter: Filter): Int
              float: Float
            }
            """.trimIndent()
    }
}
