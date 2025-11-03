@file:Suppress("ForbiddenImport")

package viaduct.mapping.graphql

import graphql.Scalars
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bigInt
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.mapping.test.ir

class GJValueConvTest : KotestPropertyBase() {
    private val schema = mkSchema(
        """
            input SimpleInput {
              x:Int
            }
            input CyclicInput {
              inp: CyclicInput
            }
            input InputWithDefaults {
              f1:Int = 1
              f2:Int! = 2
              f3:Int = null
            }
            enum E { A, B, C }
            input ComplexInput {
              f1: Short
              f2: Long
              f3: E
            }
        """.trimIndent()
    )

    private fun types(
        schema: GraphQLSchema = this@GJValueConvTest.schema,
        includeScalars: Boolean = true,
        includeBackingData: Boolean = false,
        includeInputObjects: Boolean = true,
        includeEnums: Boolean = true,
        includeNonNulls: Boolean = true,
        includeLists: Boolean = true
    ): List<GraphQLType> =
        // generate a list of unwrapped types
        schema.allTypesAsList.filter {
            when (it) {
                is GraphQLScalarType -> when {
                    it.name == "BackingData" -> includeScalars && includeBackingData
                    else -> includeScalars
                }
                is GraphQLInputObjectType -> includeInputObjects
                is GraphQLEnumType -> includeEnums
                else -> false
            }
        }.flatMap { unwrappedType ->
            // add decorators
            buildList {
                add(unwrappedType)
                if (includeNonNulls) {
                    add(GraphQLNonNull(unwrappedType))
                }
                if (includeLists) {
                    add(GraphQLList(unwrappedType))
                    add(GraphQLList(GraphQLList(unwrappedType)))
                }
                if (includeNonNulls && includeLists) {
                    add(GraphQLNonNull(GraphQLList(unwrappedType)))
                    add(GraphQLList(GraphQLNonNull(unwrappedType)))
                }
            }
        }

    @Test
    fun `arbitrary ir values can be roundtripped through GJValue`(): Unit =
        runBlocking {
            val arb = arbitrary {
                val type = Arb.of(types()).bind()
                val ir = Arb.ir(schema, type).bind()
                type to ir
            }

            arb.forAll { (type, ir) ->
                val conv = GJValueConv(type)
                val ir2 = conv(conv.invert(ir))
                ir == ir2
            }
        }

    @Test
    fun `nonNull -- throws when null`() {
        val conv = GJValueConv(GraphQLNonNull(Scalars.GraphQLInt))
        assertThrows<IllegalArgumentException> { conv(NullValue.of()) }
        assertThrows<IllegalArgumentException> { conv.invert(IR.Value.Null) }
    }

    @Test
    fun `object -- ignores unknown GJ fields`() {
        val conv = GJValueConv(schema.getTypeAs<GraphQLInputType>("SimpleInput"))

        val ir = conv(
            ObjectValue(
                listOf(
                    ObjectField("unknown", NullValue.of())
                )
            )
        ) as IR.Value.Object
        assertFalse(ir.fields.contains("unknown"))
    }

    @Test
    fun `object -- throws on unknown IR fields`() {
        val conv = GJValueConv(schema.getTypeAs<GraphQLInputType>("SimpleInput"))

        assertThrows<IllegalArgumentException> {
            conv.invert(
                IR.Value.Object("SimpleInput", mapOf("unknown" to IR.Value.Null))
            )
        }
    }

    @Test
    fun `Int-ish values can be roundtripped with range check`() {
        checkRange("Byte", Byte.MIN_VALUE to Byte.MAX_VALUE)
        checkRange("Short", Short.MIN_VALUE to Short.MAX_VALUE)
        checkRange("Int", Int.MIN_VALUE to Int.MAX_VALUE)
        checkRange("Long", Long.MIN_VALUE to Long.MAX_VALUE)
    }

    private fun checkRange(
        typeName: String,
        range: Pair<Number, Number>
    ) {
        val conv = GJValueConv(schema.getType(typeName)!!)
        checkRanges(conv, range)
    }

    private fun checkRanges(
        conv: Conv<Value<*>, IR.Value>,
        range: Pair<Number, Number>
    ): Unit =
        runBlocking {
            // check values in range
            Arb.long(range.first.toLong(), range.second.toLong()).forAll { long ->
                val bigIntValue = long.toBigInteger()
                val value = IntValue(bigIntValue)

                // value can be roundtripped without throwing
                val value2 = conv.invert(conv(value)) as IntValue
                value2.value == bigIntValue
            }

            // check values out of range
            Arb.bigInt(128).forAll { bigInt ->
                val value = IntValue(bigInt)
                if (bigInt in range) {
                    // skip, covered above
                    true
                } else {
                    // assert that conv will throw
                    val result = runCatching { conv(value) }
                    result.exceptionOrNull() is ArithmeticException
                }
            }
        }

    private operator fun Pair<Number, Number>.contains(value: BigInteger): Boolean =
        first.toLong().toBigInteger() <= value &&
            second.toLong().toBigInteger() >= value
}
