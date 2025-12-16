@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.Scalars
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.take
import io.kotest.property.forAll
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.flatten
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.TypenameValueWeight
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.mkRawSelectionSet
import viaduct.engine.api.mocks.mkSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.mapping.graphql.IR
import viaduct.mapping.test.ir
import viaduct.mapping.test.objectIR

class JsonConvTest : KotestPropertyBase() {
    private val emptySchema = mkSchema(
        """
            extend type Query {
                float:Float
                id:ID
                int:Int
            }
        """.trimIndent()
    )

    @Test
    fun `Boolean -- simple`() {
        val conv = JsonConv(emptySchema, Scalars.GraphQLBoolean)
        assertEquals(IR.Value.Boolean(true), conv("true"))
        assertEquals("true", conv.invert(IR.Value.Boolean(true)))
    }

    @Test
    fun `Boolean -- arb`() {
        checkAll(emptySchema, "Boolean")
    }

    @Test
    fun `Int -- simple`() {
        val conv = JsonConv(emptySchema, Scalars.GraphQLInt)
        assertEquals(IR.Value.Number(1), conv("1"))
        assertEquals("1", conv.invert(IR.Value.Number(1)))
    }

    @Test
    fun `Int -- arb`() {
        checkAll(emptySchema, "Int")
    }

    @Test
    fun `Byte -- simple`() {
        val conv = JsonConv(emptySchema, ExtendedScalars.GraphQLByte)
        assertEquals(IR.Value.Number(1.toByte()), conv("1"))
        assertEquals("1", conv.invert(IR.Value.Number(1.toByte())))
    }

    @Test
    fun `Byte -- arb`() {
        checkAll(emptySchema, "Byte")
    }

    @Test
    fun `Short -- simple`() {
        val conv = JsonConv(emptySchema, ExtendedScalars.GraphQLShort)
        assertEquals(IR.Value.Number(1.toShort()), conv("1"))
        assertEquals("1", conv.invert(IR.Value.Number(1.toShort())))
    }

    @Test
    fun `Short -- arb`() {
        checkAll(emptySchema, "Short")
    }

    @Test
    fun `Long -- simple`() {
        // spot check
        val conv = JsonConv(emptySchema, ExtendedScalars.GraphQLLong)
        assertEquals(IR.Value.Number(1.toLong()), conv("1"))
        assertEquals("1", conv.invert(IR.Value.Number(1.toLong())))
    }

    @Test
    fun `Long -- arb`() {
        checkAll(emptySchema, "Long")
    }

    @Test
    fun `Float -- simple`() {
        val conv = JsonConv(emptySchema, Scalars.GraphQLFloat)
        assertEquals(IR.Value.Number(1.0), conv("1.0"))
        assertEquals("1.0", conv.invert(IR.Value.Number(1.0)))
    }

    @Test
    fun `Float -- arb`() {
        checkAll(emptySchema, "Float")
    }

    @Test
    fun `String -- simple`() {
        val conv = JsonConv(emptySchema, Scalars.GraphQLString)
        assertEquals(IR.Value.String("str"), conv("\"str\""))
        assertEquals("\"str\"", conv.invert(IR.Value.String("str")))
    }

    @Test
    fun `String -- arb`() {
        checkAll(emptySchema, "String")
    }

    @Test
    fun `JSON -- simple`() {
        val conv = JsonConv(emptySchema, ExtendedScalars.Json)
        assertEquals(IR.Value.String("[1]"), conv("\"[1]\""))
        assertEquals("\"[1]\"", conv.invert(IR.Value.String("[1]")))
    }

    @Test
    fun `JSON -- arb`() {
        checkAll(emptySchema, "JSON")
    }

    @Test
    fun `ID -- simple`() {
        // spot check
        val conv = JsonConv(emptySchema, Scalars.GraphQLID)
        assertEquals(IR.Value.String("id"), conv("\"id\""))
        assertEquals("\"id\"", conv.invert(IR.Value.String("id")))
    }

    @Test
    fun `ID -- arb`() {
        checkAll(emptySchema, "ID")
    }

    @Test
    fun `Date -- simple`() {
        // spot check
        val conv = JsonConv(emptySchema, ExtendedScalars.Date)
        val date = LocalDate.MAX
        assertEquals(IR.Value.Time(date), conv("\"$date\""))
        assertEquals("\"$date\"", conv.invert(IR.Value.Time(date)))
    }

    @Test
    fun `Date -- arb`() {
        checkAll(emptySchema, "Date")
    }

    @Test
    fun `DateTime -- simple`() {
        // spot check
        val conv = JsonConv(emptySchema, ExtendedScalars.DateTime)
        val inst = Instant.MAX
        assertEquals(IR.Value.Time(inst), conv("\"$inst\""))
        assertEquals("\"$inst\"", conv.invert(IR.Value.Time(inst)))
    }

    @Test
    fun `DateTime -- arb`() {
        checkAll(emptySchema, "DateTime")
    }

    @Test
    fun `Time -- simple`() {
        val conv = JsonConv(emptySchema, ExtendedScalars.Time)
        val time = OffsetTime.MAX
        assertEquals(IR.Value.Time(time), conv("\"$time\""))
        assertEquals("\"$time\"", conv.invert(IR.Value.Time(time)))
    }

    @Test
    fun `Time -- arb`() {
        checkAll(emptySchema, "Time")
    }

    @Test
    fun `BackingData`() {
        // TODO: support BackingData https://app.asana.com/1/150975571430/project/1211295233988904/task/1211525978501301
        val type = emptySchema.schema.getType("BackingData")!!
        val conv = JsonConv(emptySchema, type)

        // all values are mapped to IR null
        assertEquals(IR.Value.Null, conv("{}"))

        // all IR values are inverted to null
        assertEquals("null", conv.invert(IR.Value.Null))
        assertEquals("null", conv.invert(IR.Value.String("")))
    }

    @Test
    fun `Enum -- simple`() {
        val schema = mkSchema("enum Enum { A, B }")
        val conv = JsonConv(schema, schema.schema.getType("Enum")!!)
        assertEquals(IR.Value.String("A"), conv("\"A\""))
        assertEquals("\"A\"", conv.invert(IR.Value.String("A")))
    }

    @Test
    fun `Enum -- arb`() {
        checkAll(mkSchema("enum Enum { A, B }"), "Enum")
    }

    @Test
    fun `list`() {
        val conv = JsonConv(emptySchema, GraphQLList.list(Scalars.GraphQLInt))
        val str = "[1,null,2]"
        val ir = conv(str)
        assertEquals(
            IR.Value.List(
                listOf(IR.Value.Number(1), IR.Value.Null, IR.Value.Number(2))
            ),
            ir
        )

        val str2 = conv.invert(ir)
        assertEquals(str, str2)
    }

    @Test
    fun `nonNullable`() {
        val type = GraphQLNonNull(Scalars.GraphQLInt)
        val conv = JsonConv(emptySchema, type)

        // converts non-null values
        assertEquals(IR.Value.Number(1), conv("1"))

        // throws on null values
        assertThrows<IllegalArgumentException> { conv("null") }
        assertThrows<IllegalArgumentException> { conv.invert(IR.Value.Null) }

        checkAll(emptySchema, type)
    }

    @Test
    fun `nullable`() {
        val conv = JsonConv(emptySchema, Scalars.GraphQLInt)
        assertEquals(IR.Value.Null, conv("null"))
        assertEquals("null", conv.invert(IR.Value.Null))
    }

    @Test
    fun `input objects`() {
        val schema = mkSchema("input Input { x:Int }")
        val conv = JsonConv(schema, schema.schema.getType("Input")!!)

        val str = """{"x":1,"__typename":"Input"}"""
        val ir = conv(str)
        assertEquals(IR.Value.Object("Input", mapOf("x" to IR.Value.Number(1))), ir)
        val str2 = conv.invert(ir)
        assertEquals(str, str2)
    }

    @Test
    fun `input objects -- unknown fields`() {
        val schema = mkSchema("input Input { x:Int }")
        val conv = JsonConv(schema, schema.schema.getType("Input")!!)

        // forward conversion ignores unknown fields
        assertEquals(IR.Value.Object("Input", emptyMap()), conv("""{"unknown":1}"""))

        // inversion throws on unknown fields
        assertThrows<IllegalArgumentException> {
            conv.invert(IR.Value.Object("Input", mapOf("unknown" to IR.Value.Null)))
        }
    }

    @Test
    fun `input objects -- AddJsonTypeNameField`(): Unit =
        runBlocking {
            val schema = mkSchema("input Input { x:Int }")
            val type = schema.schema.getType("Input")!!

            Arb.enum<JsonConv.AddJsonTypenameField>().forAll { addJsonTypenameField ->
                val conv = JsonConv(schema, type, null, addJsonTypenameField)

                val ir = IR.Value.Object("Input", emptyMap())
                val str = conv.invert(ir)

                val parsed = ObjectMapper().readValue(str, Map::class.java)
                when (addJsonTypenameField) {
                    JsonConv.AddJsonTypenameField.Always -> parsed["__typename"] == "Input"
                    JsonConv.AddJsonTypenameField.Never -> "__typename" !in parsed
                }
            }
        }

    @Test
    fun `all schema scalars -- arb`() {
        val scalarTypes = emptySchema.schema.allTypesAsList
            .mapNotNull { it as? GraphQLScalarType }
            .filter { it.name != "BackingData" } // not supported
            .also {
                // sanity
                assertTrue(it.size > 5)
            }

        scalarTypes.forEach { type ->
            checkAll(emptySchema, type)
        }
    }

    @Test
    fun `output objects -- simple`() {
        val schema = mkSchema("type Obj { x:Int }")
        val conv = JsonConv(schema, schema.schema.getType("Obj")!!)

        val str = """{"x":1,"__typename":"Obj"}"""
        val ir = conv(str)
        assertEquals(
            IR.Value.Object(
                "Obj",
                mapOf(
                    "x" to IR.Value.Number(1),
                    "__typename" to IR.Value.String("Obj"),
                )
            ),
            ir
        )
        val str2 = conv.invert(ir)
        assertEquals(str, str2)
    }

    @Test
    fun `output objects -- unknown fields`() {
        val schema = mkSchema("type Obj { x:Int }")
        val conv = JsonConv(schema, schema.schema.getType("Obj")!!)

        // forward conversion ignores unknown fields
        assertEquals(IR.Value.Object("Obj", emptyMap()), conv("""{"unknown":1}"""))

        // inversion throws on unknown fields
        assertThrows<IllegalArgumentException> {
            conv.invert(IR.Value.Object("Obj", mapOf("unknown" to IR.Value.Null)))
        }
    }

    @Test
    fun `output objects -- cyclic`() {
        val schema = mkSchema("type Obj { x:Obj }")
        val conv = JsonConv(schema, schema.schema.getType("Obj")!!)

        val str = """{"x":{"x":null,"__typename":"Obj"},"__typename":"Obj"}"""
        val ir = conv(str)
        assertEquals(
            IR.Value.Object(
                "Obj",
                mapOf(
                    "x" to IR.Value.Object(
                        "Obj",
                        mapOf(
                            "x" to IR.Value.Null,
                            "__typename" to IR.Value.String("Obj")
                        )
                    ),
                    "__typename" to IR.Value.String("Obj")
                )
            ),
            ir
        )
        val str2 = conv.invert(ir)
        assertEquals(str, str2)
    }

    @Test
    fun `output objects -- AddJsonTypeNameField`(): Unit =
        runBlocking {
            val schema = mkSchema("type Obj { x:Int }")
            val type = schema.schema.getType("Obj")!!

            Arb.enum<JsonConv.AddJsonTypenameField>().forAll { addJsonTypenameField ->
                val conv = JsonConv(schema, type, null, addJsonTypenameField)

                val ir = IR.Value.Object("Obj", emptyMap())
                val str = conv.invert(ir)

                val parsed = ObjectMapper().readValue(str, Map::class.java)
                when (addJsonTypenameField) {
                    JsonConv.AddJsonTypenameField.Always -> parsed["__typename"] == "Obj"
                    JsonConv.AddJsonTypenameField.Never -> "__typename" !in parsed
                }
            }
        }

    @Test
    fun `output objects with selections -- does not conv unselected fields`() {
        Fixture("type Obj { x:Int, y:Int }") {
            val conv = JsonConv(
                schema,
                graphQLType("Obj"),
                mkRawSelectionSet("Obj", "x"),
                JsonConv.AddJsonTypenameField.Never
            )

            // "y" is not selected
            assertEquals(
                IR.Value.Object("Obj", "x" to IR.Value.Number(1)),
                conv("""{"x":1,"y":1}""")
            )
            assertThrows<IllegalArgumentException> {
                conv.invert(IR.Value.Object("Obj", mapOf("y" to IR.Value.Number(1))))
            }
        }
    }

    @Test
    fun `output objects with selections -- convs aliased fields`() {
        Fixture("type Obj { x:Int }") {
            val conv = JsonConv(
                schema,
                graphQLType("Obj"),
                mkRawSelectionSet("Obj", "a:x, b:x"),
                JsonConv.AddJsonTypenameField.Never
            )

            // x is selected multiple times, with aliases "a" and "b"
            assertRoundtrip(
                conv,
                """{"a":1,"b":2}""",
                IR.Value.Object(
                    "Obj",
                    "a" to IR.Value.Number(1),
                    "b" to IR.Value.Number(2)
                )
            )
        }
    }

    @Test
    fun `output objects with selections -- the same type can be selected multiple times with different selections`() {
        Fixture("type Obj { x:Int, obj:Obj }") {
            val conv = JsonConv(
                schema,
                graphQLType("Obj"),
                mkRawSelectionSet("Obj", "a:x, obj { b:x }"),
                JsonConv.AddJsonTypenameField.Never
            )

            assertRoundtrip(
                conv,
                """{"a":1,"obj":{"b":2}}""",
                IR.Value.Object(
                    "Obj",
                    "a" to IR.Value.Number(1),
                    "obj" to IR.Value.Object(
                        "Obj",
                        "b" to IR.Value.Number(2)
                    )
                )
            )
        }
    }

    @Test
    fun `output obj with selections -- a field can be selected multiple times`() {
        Fixture("type Obj { x:Int }") {
            val conv = JsonConv(
                schema,
                graphQLType("Obj"),
                mkRawSelectionSet("Obj", "a:x, b:x"),
                JsonConv.AddJsonTypenameField.Never
            )
            assertRoundtrip(
                conv,
                """{"a":1,"b":2}""",
                IR.Value.Object(
                    "Obj",
                    "a" to IR.Value.Number(1),
                    "b" to IR.Value.Number(2),
                )
            )
        }
    }

    @Test
    fun `unions`() {
        val schema = mkSchema(
            """
                type A { x:Int }
                type B { y:Int }
                union U = A | B
            """.trimIndent()
        )
        val conv = JsonConv(schema, schema.schema.getType("U")!!)

        val str = """{"x":1,"__typename":"A"}"""
        val ir = conv(str)
        assertEquals(
            IR.Value.Object(
                "A",
                mapOf("x" to IR.Value.Number(1), "__typename" to IR.Value.String("A"))
            ),
            ir
        )
        val str2 = conv.invert(ir)
        assertEquals(str, str2)
    }

    @Test
    fun `unions -- throws when missing __typename`() {
        val schema = mkSchema(
            """
                type A { x:Int }
                union U = A
            """.trimIndent()
        )
        val conv = JsonConv(schema, schema.schema.getType("U")!!)

        assertThrows<IllegalArgumentException> {
            conv("""{"x":1}""")
        }
    }

    @Test
    fun `unions with selections -- simple`() {
        Fixture(
            """
                type A { x:Int }
                union U = A
            """.trimIndent()
        ) {
            val conv = JsonConv(
                schema,
                graphQLType("U"),
                mkRawSelectionSet("U", "... on A { a:x }")
            )
            assertRoundtrip(
                conv,
                """{"a":1,"__typename":"A"}""",
                IR.Value.Object("A", "a" to IR.Value.Number(1))
            )
        }
    }

    @Test
    fun `interfaces`() {
        val schema = mkSchema(
            """
                type A implements I { x:Int }
                interface I { x:Int }
            """.trimIndent()
        )
        val conv = JsonConv(schema, schema.schema.getType("I")!!)

        val str = """{"x":1,"__typename":"A"}"""
        val ir = conv(str)
        assertEquals(
            IR.Value.Object(
                "A",
                mapOf("x" to IR.Value.Number(1), "__typename" to IR.Value.String("A"))
            ),
            ir
        )
        val str2 = conv.invert(ir)
        assertEquals(str, str2)
    }

    @Test
    fun `interfaces -- throws when missing __typename`() {
        val schema = mkSchema(
            """
                type A implements I { x:Int }
                interface I { x:Int }
            """.trimIndent()
        )
        val conv = JsonConv(schema, schema.schema.getType("I")!!)

        assertThrows<IllegalArgumentException> {
            conv("""{"x":1}""")
        }
    }

    @Test
    fun `interfaces with selections -- simple`() {
        Fixture(
            """
                type A implements I { x:Int }
                interface I { x:Int }
            """.trimIndent()
        ) {
            val conv = JsonConv(
                schema,
                graphQLType("I"),
                mkRawSelectionSet("I", "... on A { a:x }")
            )
            assertRoundtrip(
                conv,
                """{"a":1,"__typename":"A"}""",
                IR.Value.Object("A", "a" to IR.Value.Number(1))
            )
        }
    }

    @Test
    fun `roundtrips arb ir for simple objects`(): Unit =
        runBlocking {
            val schema = mkSchema("type Obj { x:Int }")
            val cfg = Config.default + (TypenameValueWeight to 1.0)
            Arb.objectIR(schema.schema, cfg).forAll { ir ->
                val type = schema.schema.getObjectType(ir.name)
                val roundtripper = JsonConv(schema, type).let { conv ->
                    conv.inverse() andThen conv
                }

                val ir2 = roundtripper(ir)
                ir == ir2
            }
        }

    @Test
    fun `roundtrips arb ir for arb objects`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (GenInterfaceStubsIfNeeded to true) +
                (TypenameValueWeight to 1.0)

            // Arb<Triple(schema, type, ir)>
            val arb = Arb
                .graphQLSchema(cfg)
                .map { schema ->
                    Arb
                        .of(schema.allTypesAsList)
                        .flatMap { type ->
                            Arb.ir(schema, type, cfg).map { ir ->
                                Triple(schema, type, ir)
                            }
                        }.take(100, randomSource)
                        .toList()
                }.flatten()

            arb.forAll { (schema, type, ir) ->
                val roundtripper = JsonConv(ViaductSchema(schema), type)
                    .let { conv -> conv.inverse() andThen conv }

                val roundtripped = roundtripper(ir)
                ir == roundtripped
            }
        }

    private fun checkAll(
        schema: ViaductSchema,
        typeName: String
    ): Unit = checkAll(schema, schema.schema.getType(typeName)!!)

    private fun checkAll(
        schema: ViaductSchema,
        type: GraphQLType
    ): Unit =
        runBlocking {
            val roundtripper = JsonConv(schema, type).let { conv ->
                conv.inverse() andThen conv
            }

            Arb.ir(schema.schema, type).forAll { ir ->
                ir == roundtripper(ir)
            }
        }

    private class Fixture(sdl: String, fn: Fixture.() -> Unit) {
        val schema = mkSchema(sdl)

        init {
            fn(this)
        }

        fun graphQLType(name: String): GraphQLType = schema.schema.getType(name)!!

        fun mkRawSelectionSet(
            selectionsType: String,
            selections: String,
            variables: Map<String, Any?> = emptyMap()
        ): RawSelectionSet =
            mkRawSelectionSet(
                SelectionsParser.parse(selectionsType, selections),
                schema,
                variables
            )
    }
}
