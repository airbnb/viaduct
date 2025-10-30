package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.ScalarValue
import graphql.language.StringValue
import graphql.language.Value
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema

class ValueMapperTest {
    private val sdl =
        """
        type Query { empty: Int }
        schema { query: Query }

        scalar Date
        scalar DateTime
        scalar Time
        scalar Long
        scalar Short

        # builtin scalars can get removed if not used
        type Keep {
          id: ID
          float: Float
        }

        input Input { int: Int! list: [[Int]!] }
        enum Enum { A }
        """.trimIndent()

    private val schema = GJSchema.fromRegistry(readTypes(sdl))

    private fun expr(name: String): ViaductSchema.TypeExpr = schema.types[name]!!.asTypeExpr()

    private fun assertEquals(
        a: Value<*>?,
        b: Value<*>?
    ) {
        when (a) {
            null -> assertNull(b)
            is NullValue, is ScalarValue<*>, is EnumValue -> assert(a.isEqualTo(b))
            is ArrayValue -> {
                assert(b is ArrayValue)
                b as ArrayValue
                assertEquals(a.values.size, b.values.size)
                a.values.zip(b.values).forEach { (av, bv) -> assert(av == bv) }
            }

            is ObjectValue -> {
                assert(b is ObjectValue)
                b as ObjectValue
                val aMap = a.objectFields.associateBy({ it.name }, { it.value })
                val bMap = b.objectFields.associateBy({ it.name }, { it.value })
                assertEquals(aMap.keys, bMap.keys)
                aMap.forEach { (k, v) -> assert(v == bMap[k]) }
            }

            else -> fail("Unexpected value : $a")
        }
    }

    @Test
    fun defaultValueMapper() {
        expr("Input").let { type ->
            assertNull(defaultValueMapper(type, null))
            assertNull(defaultValueMapper(type, NullValue.of()))
            listOf(
                StringValue("a"),
                IntValue.of(42),
                FloatValue.of(3.14),
                ArrayValue(emptyList()),
                ArrayValue(listOf(NullValue.of())),
                ArrayValue(listOf(StringValue("a"), StringValue("b"))),
                ObjectValue(emptyList()),
                ObjectValue(
                    listOf(
                        ObjectField("a", NullValue.of()),
                        ObjectField("b", StringValue.of("b"))
                    )
                )
            ).forEach {
                assertEquals(defaultValueMapper(type, it), it)
            }
        }
    }

    @Test
    fun externalToLiteral() {
        fun assertConverts(
            typeName: String,
            value: Any?,
            expected: Value<*>
        ) = expr(typeName).let { type ->
            assertEquals(externalToLiteral(type, value), expected)
        }

        assertConverts("String", null, NullValue.of())
        assertConverts("String", "a", StringValue("a"))
        assertConverts("Boolean", true, BooleanValue(true))
        LocalDate.MAX.let { date ->
            assertConverts("Date", date, StringValue(date.toString()))
        }
        Instant.MAX.let { inst ->
            assertConverts("DateTime", inst, StringValue(inst.toString()))
        }
        assertConverts("Float", 3.14, FloatValue.of(3.14))
        assertConverts("Int", 42, IntValue.of(42))
        assertConverts("Long", 42L, IntValue.of(42))
        assertConverts("Short", 42.toShort(), IntValue.of(42))
        assertConverts("ID", "a", StringValue.of("a"))
        assertConverts("String", "a", StringValue.of("a"))
        OffsetTime.MAX.let { time ->
            assertConverts("Time", time, StringValue(time.toString()))
        }
    }
}
