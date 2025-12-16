@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import graphql.Scalars
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.of
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
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.TypenameValueWeight
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.engineObjectsAreEquivalent
import viaduct.engine.api.mocks.mkRawSelectionSet
import viaduct.engine.api.mocks.mkSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR
import viaduct.mapping.test.ir

class EngineValueConvTest : KotestPropertyBase() {
    private val emptySchema: ViaductSchema = mkSchema("extend type Query { x:Int }")

    @Test
    fun `roundtrips arbitrary ir for arbitrary schemas`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (GenInterfaceStubsIfNeeded to true) +
                // When converting an abstract value from IR to its engine value representation, a __typename
                // field will be introduced to record the concrete object type. This breaks roundtrippability
                // if the original IR did not have a __typename field.
                // We configure the generator to always add __typename, ensuring that the roundtripped IR is
                // exactly the same as the input IR
                (TypenameValueWeight to 1.0)

            // generate arbitrary triples of (ViaductSchema, GraphQLType, IR)
            val arb = Arb.graphQLSchema(cfg)
                .flatMap { gjSchema ->
                    val vschema = ViaductSchema(gjSchema)
                    arbitrary {
                        val type = Arb.of(gjSchema.allTypesAsList)
                            .flatMap { type ->
                                // add type wrappers
                                Arb.of(
                                    // undecorated
                                    type,
                                    // single list
                                    GraphQLList.list(type),
                                    // non-null
                                    GraphQLNonNull.nonNull(type),
                                    // double list
                                    GraphQLList.list(GraphQLList.list(type)),
                                    // list of non-null
                                    GraphQLList.list(GraphQLNonNull.nonNull(type)),
                                    // non-null of list
                                    GraphQLNonNull.nonNull(GraphQLList.list(type)),
                                )
                            }.bind()
                        val ir = Arb.ir(gjSchema, type, cfg).bind()
                        Triple(vschema, type, ir)
                    }
                }
            arb.forAll { (schema, type, ir) ->
                val conv = EngineValueConv(schema, type, null)
                val ir2 = conv(conv.invert(ir))
                ir == ir2
            }
        }

    @Test
    fun `non-null`() {
        val conv = EngineValueConv(emptySchema, GraphQLNonNull.nonNull(Scalars.GraphQLInt), null)
        // invokes underlying conv when non-null
        assertRoundtrip(conv, 1, IR.Value.Number(1))

        // throws on null engine value
        assertThrows<IllegalArgumentException> { conv(null) }

        // throws on IR.Value.Null
        assertThrows<IllegalArgumentException> { conv.invert(IR.Value.Null) }
    }

    @Test
    fun `nullable`() {
        val conv = EngineValueConv(emptySchema, Scalars.GraphQLInt, null)
        assertRoundtrip(conv, null, IR.Value.Null)
    }

    @Test
    fun `list`() {
        val conv = EngineValueConv(emptySchema, GraphQLList(Scalars.GraphQLInt), null)
        assertRoundtrip(
            conv,
            listOf(1, 2),
            IR.Value.List(listOf(IR.Value.Number(1), IR.Value.Number(2)))
        )
    }

    @Test
    fun `Byte -- simple`() {
        val conv = EngineValueConv(emptySchema, ExtendedScalars.GraphQLByte, null)
        assertRoundtrip(conv, 1.toByte(), IR.Value.Number(1.toByte()))
    }

    @Test
    fun `Short -- simple`() {
        val conv = EngineValueConv(emptySchema, ExtendedScalars.GraphQLShort, null)
        assertRoundtrip(conv, 1.toShort(), IR.Value.Number(1.toShort()))
    }

    @Test
    fun `Int -- simple`() {
        val conv = EngineValueConv(emptySchema, Scalars.GraphQLInt, null)
        assertRoundtrip(conv, 1, IR.Value.Number(1))
    }

    @Test
    fun `Long -- simple`() {
        val conv = EngineValueConv(emptySchema, ExtendedScalars.GraphQLLong, null)
        assertRoundtrip(conv, 1L, IR.Value.Number(1L))
    }

    @Test
    fun `Float -- simple`() {
        val conv = EngineValueConv(emptySchema, Scalars.GraphQLFloat, null)
        assertRoundtrip(conv, 1.0, IR.Value.Number(1.0))
    }

    @Test
    fun `DateTime -- simple`() {
        val conv = EngineValueConv(emptySchema, ExtendedScalars.DateTime, null)
        assertRoundtrip(conv, Instant.MAX, IR.Value.Time(Instant.MAX))
    }

    @Test
    fun `Date -- simple`() {
        val conv = EngineValueConv(emptySchema, ExtendedScalars.Date, null)
        assertRoundtrip(conv, LocalDate.MAX, IR.Value.Time(LocalDate.MAX))
    }

    @Test
    fun `Time -- simple`() {
        val conv = EngineValueConv(emptySchema, ExtendedScalars.Time, null)
        assertRoundtrip(conv, OffsetTime.MAX, IR.Value.Time(OffsetTime.MAX))
    }

    @Test
    fun `Boolean -- simple`() {
        val conv = EngineValueConv(emptySchema, Scalars.GraphQLBoolean, null)
        assertRoundtrip(conv, true, IR.Value.Boolean(true))
    }

    @Test
    fun `String -- simple`() {
        val conv = EngineValueConv(emptySchema, Scalars.GraphQLString, null)
        assertRoundtrip(conv, "1", IR.Value.String("1"))
    }

    @Test
    fun `ID -- simple`() {
        val conv = EngineValueConv(emptySchema, Scalars.GraphQLID, null)
        assertRoundtrip(conv, "1", IR.Value.String("1"))
    }

    @Test
    fun `JSON`() {
        val conv = EngineValueConv.json

        // null
        assertRoundtrip(conv, null, IR.Value.String("null"))

        // int scalar
        assertRoundtrip(conv, 1, IR.Value.String("1"))

        // string scalar
        assertRoundtrip(conv, "str", IR.Value.String("\"str\""))

        // list
        assertRoundtrip(conv, listOf(1, 2), IR.Value.String("[1,2]"))

        // map
        assertRoundtrip(conv, mapOf("foo" to 1), IR.Value.String("{\"foo\":1}"))
    }

    @Test
    fun `enum -- simple`() {
        val schema = mkSchema("enum E { A, B }")
        val conv = EngineValueConv(schema, schema.schema.getType("E")!!, null)

        assertRoundtrip(conv, "A", IR.Value.String("A"))

        // can conv unknown values
        assertRoundtrip(conv, "Unknown", IR.Value.String("Unknown"))
    }

    @Test
    fun `input object -- simple`() {
        val schema = mkSchema("input Inp { x:Int }")
        val conv = EngineValueConv(schema, schema.schema.getType("Inp")!!, null)

        assertRoundtrip(
            conv,
            mapOf("x" to 1),
            IR.Value.Object("Inp", mapOf("x" to IR.Value.Number(1))),
        )
    }

    @Test
    fun `input object -- cyclic`() {
        val schema = mkSchema("input Inp { inp:Inp }")
        val conv = EngineValueConv(schema, schema.schema.getType("Inp")!!, null)

        assertRoundtrip(
            conv,
            mapOf("inp" to mapOf("inp" to null)),
            IR.Value.Object(
                "Inp",
                mapOf("inp" to IR.Value.Object("Inp", mapOf("inp" to IR.Value.Null)))
            )
        )
    }

    @Test
    fun `object -- simple`() {
        val schema = mkSchema("type Obj { x:Int }")
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(schema, schema.schema.getType("Obj")!!, null)

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(obj, mapOf("x" to 1)),
            IR.Value.Object("Obj", mapOf("x" to IR.Value.Number(1)))
        )
    }

    @Test
    fun `object -- __typename`() {
        val schema = mkSchema("type Obj { x:Int }")
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(schema, schema.schema.getObjectType("Obj"), null)

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(obj, mapOf("__typename" to "Obj")),
            IR.Value.Object("Obj", mapOf("__typename" to IR.Value.String("Obj")))
        )
    }

    @Test
    fun `object -- cyclic`() {
        val schema = mkSchema("type Obj { obj:Obj }")
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(schema, obj, null)

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(
                obj,
                mapOf("obj" to ResolvedEngineObjectData(obj, mapOf("obj" to null)))
            ),
            IR.Value.Object(
                "Obj",
                mapOf("obj" to IR.Value.Object("Obj", mapOf("obj" to IR.Value.Null)))
            )
        )
    }

    @Test
    fun `object with selections -- simple`() {
        val schema = mkSchema("type Obj { x:Int }")
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(
            schema,
            obj,
            mkRawSelectionSet(schema, "Obj", "a:x")
        )

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(obj, mapOf("a" to 1)),
            IR.Value.Object("Obj", "a" to IR.Value.Number(1))
        )
    }

    @Test
    fun `object with selections -- the same type can be selected multiple times with different selections`() {
        val schema = mkSchema("type Obj { x:Int, obj:Obj }")
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(
            schema,
            obj,
            mkRawSelectionSet(
                schema,
                "Obj",
                "a:x, obj { b:x }"
            )
        )

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(
                obj,
                mapOf(
                    "a" to 1,
                    "obj" to ResolvedEngineObjectData(
                        obj,
                        mapOf("b" to 2)
                    )
                )
            ),
            IR.Value.Object(
                "Obj",
                "a" to IR.Value.Number(1),
                "obj" to IR.Value.Object("Obj", "b" to IR.Value.Number(2))
            )
        )
    }

    @Test
    fun `object with selections -- a field can be selected multiple times`() {
        val schema = mkSchema("type Obj { x:Int }")
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(schema, obj, mkRawSelectionSet(schema, "Obj", "a:x, b:x"))

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(obj, mapOf("a" to 1, "b" to 2)),
            IR.Value.Object("Obj", "a" to IR.Value.Number(1), "b" to IR.Value.Number(2))
        )
    }

    @Test
    fun `union -- simple`() {
        val schema = mkSchema(
            """
                type Obj { x:Int }
                union U = Obj
            """.trimIndent()
        )
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(schema, schema.schema.getType("U")!!, null)

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(
                obj,
                mapOf("x" to 1, "__typename" to "Obj")
            ),
            IR.Value.Object(
                "Obj",
                mapOf(
                    "x" to IR.Value.Number(1),
                    "__typename" to IR.Value.String("Obj")
                )
            )
        )
    }

    @Test
    fun `interface -- simple`() {
        val schema = mkSchema(
            """
                interface I { x:Int }
                type Obj implements I { x:Int }
            """.trimIndent()
        )
        val obj = schema.schema.getObjectType("Obj")
        val conv = EngineValueConv(schema, schema.schema.getType("I")!!, null)

        assertRoundtrip(
            conv,
            ResolvedEngineObjectData(
                obj,
                mapOf("x" to 1, "__typename" to "Obj")
            ),
            IR.Value.Object(
                "Obj",
                mapOf(
                    "x" to IR.Value.Number(1),
                    "__typename" to IR.Value.String("Obj")
                )
            )
        )
    }
}

internal fun <From, To> assertRoundtrip(
    conv: Conv<From, To>,
    from: From,
    to: To,
) {
    assertEquals(to, conv(from))
    assertTrue(valuesEqual(from, conv.invert(to)))
}

internal fun valuesEqual(
    a: Any?,
    b: Any?
): Boolean =
    when {
        a == null || b == null -> (a == null) == (b == null)
        a is EngineObjectData.Sync && b is EngineObjectData.Sync ->
            engineObjectsAreEquivalent(a, b)
        else -> (a == b)
    }

private fun mkRawSelectionSet(
    schema: ViaductSchema,
    selectionsType: String,
    selections: String,
    variables: Map<String, Any?> = emptyMap()
): RawSelectionSet =
    mkRawSelectionSet(
        SelectionsParser.parse(selectionsType, selections),
        schema,
        variables
    )
