@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.exhaustive.boolean
import io.kotest.property.exhaustive.exhaustive
import io.kotest.property.forAll
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.graphql.schema.ViaductSchema
import viaduct.mapping.graphql.RawENull
import viaduct.mapping.graphql.RawINull
import viaduct.mapping.graphql.RawInput
import viaduct.mapping.graphql.RawList
import viaduct.mapping.graphql.RawObject
import viaduct.mapping.graphql.RawValue
import viaduct.mapping.graphql.RawValue.Companion.enum
import viaduct.mapping.graphql.RawValue.Companion.scalar
import viaduct.mapping.graphql.ValueMapper

private typealias Roundtripper<T> = ValueMapper<T, RawValue, RawValue>

private fun <T> Roundtripper<T>.roundtrips(
    t: T,
    value: RawValue
) = value == this(t, value)

class ValueMapperTest : KotestPropertyBase() {
    private class Fixture(sdl: String = "", fn: suspend Fixture.() -> Unit) {
        val gjSchema = mkGJSchema(sdl)
        val viaductSchema = mkViaductSchema(sdl)
        val resolver = TypeReferenceResolver.fromSchema(gjSchema)

        // bridge type roundtripper: RawValue -> Value -> RawValue
        val bridgeGjRoundtripper = BridgeRawToGJ.map(BridgeGJToRaw)

        // gj type roundtripper: RawValue -> Value -> RawValue
        val gjGjRoundtripper = GJRawToGJ(resolver).map(GJGJToRaw(resolver))

        // gj type roundtripper: RawValue -> Kotlin -> RawValue
        val gjKotlinRoundtripper = RawToKotlin.map(GJKotlinToRaw(resolver))

        init {
            runBlocking { fn() }
        }

        fun roundtrips(
            coord: String,
            value: RawValue
        ): Boolean {
            val results = listOf(
                bridgeGjRoundtripper.roundtrips(viaductSchema.type(coord), value),
                gjGjRoundtripper.roundtrips(gjSchema.type(coord), value),
                gjKotlinRoundtripper.roundtrips(gjSchema.type(coord), value)
            )
            return results.all { it }
        }
    }

    @Test
    fun mk() {
        val mapper = ValueMapper.mk { _: Unit, x: Int -> x.toString() }
        assertEquals("1", mapper(Unit, 1))
    }

    @Test
    fun map() {
        val a = ValueMapper.mk { _: Unit, x: Int -> x + 1 }
        val b = ValueMapper.mk { _: Unit, x: Int -> x.toString() }
        assertEquals("2", a.map(b)(Unit, 1))
    }

    @Test
    fun `roundtrip enull`() {
        Fixture {
            assertTrue(roundtrips("Int", RawENull))
        }
    }

    @Test
    fun `roundtrip int scalar`() {
        Fixture {
            runBlocking {
                Arb.int().forAll { int ->
                    roundtrips("Int", int.scalar)
                }
            }
        }
    }

    @Test
    fun `roundtrip boolean scalar`() {
        Fixture {
            runBlocking {
                Exhaustive.boolean().forAll { bool ->
                    roundtrips("Boolean", bool.scalar)
                }
            }
        }
    }

    @Test
    fun `roundtrip float scalar`() {
        Fixture {
            // from the graphql spec:
            //   Non-finite floating-point internal values (NaN and Infinity) cannot be coerced to Float
            //   and must raise a field error.
            Arb.double(includeNonFiniteEdgeCases = false)
                // Arb.double can generate a signed zero value, "-0.0", which is both difficult to filter
                // out and doesn't roundtrip through BigDecimal, which GJ uses internally.
                // Normalize values through BigDecimal before testing
                .map { BigDecimal.valueOf(it).toDouble() }
                .forAll { double ->
                    roundtrips("Float", double.scalar)
                }
        }
    }

    @Test
    fun `roundtrip string scalar`() {
        Fixture {
            Arb.string().forAll { str ->
                roundtrips("String", str.scalar)
            }
        }
    }

    @Test
    fun `roundtrip enum`() {
        Fixture("enum Enum { A, B, C }") {
            listOf("A", "B", "C").exhaustive().forAll { v ->
                roundtrips("Enum", v.enum)
            }
        }
    }

    @Test
    fun `roundtrip input`() {
        Fixture("input Input { f1: Int, f2: Int!, f3: Int=3, f4: Int!=4 }") {
            val inputs = Arb.bind(
                Arb.int().orNull(), // null is enull
                Arb.int(),
                Arb.int().orNull(), // null is enull
                Arb.int().orNull() // null is inull
            ) { f1, f2, f3, f4 ->
                RawInput(
                    listOf(
                        "f1" to (f1?.scalar ?: RawENull),
                        "f2" to f2.scalar,
                        "f3" to (f3?.scalar ?: RawENull),
                        "f4" to (f4?.let { it.scalar } ?: RawINull)
                    )
                )
            }

            inputs.forAll {
                roundtrips("Input", it)
            }
        }
    }

    @Test
    fun `roundtrip list`() {
        Fixture("input Container { x: [Int] }") {
            Arb.list(Arb.int().orNull())
                .forAll { ints ->
                    val list = RawList(ints.map { it?.scalar ?: RawENull })
                    roundtrips("Container.x", list)
                }
        }
    }

    @Test
    fun `obj is unconvertible`() {
        // output values are not modeled by GJ values
        Fixture("type Obj { x: Int }") {
            val objs = Arb.int().orNull().map { x ->
                RawObject("Obj", listOf("x" to (x?.scalar ?: RawENull)))
            }
            objs.forAll { obj ->
                val result = runCatching {
                    roundtrips("Obj", obj)
                }
                result.exceptionOrNull() is IllegalArgumentException
            }
        }
    }
}

private fun asCoordinate(name: String): Pair<String, String?> =
    name.splitToSequence('.').toList().let {
        if (it.size == 1) {
            name to null
        } else if (it.size == 2) {
            it[0] to it[1]
        } else {
            throw IllegalArgumentException()
        }
    }

private inline fun <reified T : GraphQLType> GraphQLSchema.type(coord: String) =
    asCoordinate(coord).let { (type, field) ->
        val schemaType = (this.getType(type)!!)
        if (field != null) {
            when (schemaType) {
                is GraphQLInputObjectType -> schemaType.getField(field)!!.type as T
                is GraphQLFieldsContainer -> schemaType.getField(field)!!.type as T
                else -> throw IllegalArgumentException()
            }
        } else {
            schemaType as T
        }
    }

private fun ViaductSchema.type(coord: String) =
    asCoordinate(coord).let { (type, field) ->
        this.types[type]!!.asTypeExpr().let {
            if (field != null) {
                (it.baseTypeDef as ViaductSchema.Record).field(field)!!.type
            } else {
                it
            }
        }
    }
