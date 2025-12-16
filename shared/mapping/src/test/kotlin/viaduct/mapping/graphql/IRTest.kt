@file:Suppress("ForbiddenImport", "IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE")

package viaduct.mapping.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.bigInt
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.instant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.localTime
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.short
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.zoneOffset
import io.kotest.property.forAll
import java.time.OffsetTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.graphQLFieldName
import viaduct.arbitrary.graphql.graphQLName
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.mapping.test.DomainValidator

class IRTest : KotestPropertyBase() {
    @Test
    fun `IR -- objectToIR`() {
        assertEquals(Conv.identity<IR.Value.Object>(), IR.conv)
    }

    @Test
    fun `Boolean`(): Unit =
        runBlocking {
            Arb.boolean().forAll { b ->
                IR.Value.Boolean(b).value == b
            }
        }

    @Test
    fun `Number -- int`(): Unit =
        runBlocking {
            Arb.int().forAll {
                val ir = IR.Value.Number(it)
                ir.value == it && ir.int == it
            }
        }

    @Test
    fun `Number -- bigInteger`(): Unit =
        runBlocking {
            Arb.bigInt(512).forAll {
                val ir = IR.Value.Number(it)
                ir.bigInteger == it && ir.value == it
            }
        }

    @Test
    fun `Number -- float`(): Unit =
        runBlocking {
            // exclude nonFiniteEdgeCases, which have fundamentally different identity properties
            //  See IEEE 754 Rule for NaN: NaN is unequal to any other value, including itself
            Arb.float(includeNonFiniteEdgeCases = false).forAll {
                val ir = IR.Value.Number(it)
                ir.float == it && ir.value == it
            }
        }

    @Test
    fun `Number -- double`(): Unit =
        runBlocking {
            // exclude nonFiniteEdgeCases, which have fundamentally different identity properties
            //  See IEEE 754 Rule for NaN: NaN is unequal to any other value, including itself
            Arb.double(includeNonFiniteEdgeCases = false).forAll {
                val ir = IR.Value.Number(it)
                ir.double == it && ir.value == it
            }
        }

    @Test
    fun `Number -- bigdecimal`(): Unit =
        runBlocking {
            Arb.bigDecimal().forAll {
                val ir = IR.Value.Number(it)
                ir.bigDecimal == it && ir.value == it
            }
        }

    @Test
    fun `Number -- long`(): Unit =
        runBlocking {
            Arb.long().forAll {
                val ir = IR.Value.Number(it)
                ir.long == it && ir.value == it
            }
        }

    @Test
    fun `Number -- short`(): Unit =
        runBlocking {
            Arb.short().forAll {
                val ir = IR.Value.Number(it)
                ir.short == it && ir.value == it
            }
        }

    @Test
    fun `Number -- byte`(): Unit =
        runBlocking {
            Arb.byte().forAll {
                val ir = IR.Value.Number(it)
                ir.byte == it && ir.value == it
            }
        }

    @Test
    fun `String`(): Unit =
        runBlocking {
            Arb.string().forAll { IR.Value.String(it).value == it }
        }

    @Test
    fun `List`(): Unit =
        runBlocking {
            val arb = Arb.list(Arb.int().map(IR.Value::Number))
            arb.forAll { IR.Value.List(it).value == it }
        }

    @Test
    fun `List -- vararg ctor`() {
        // empty
        assertEquals(
            IR.Value.List(listOf()),
            IR.Value.List()
        )

        assertEquals(
            IR.Value.List(
                listOf(IR.Value.Number(1), IR.Value.Null)
            ),
            IR.Value.List(
                IR.Value.Number(1),
                IR.Value.Null
            )
        )
    }

    @Test
    fun `Time -- instant`(): Unit =
        runBlocking {
            Arb.instant().forAll {
                val ir = IR.Value.Time(it)
                ir.instant == it && ir.value == it
            }
        }

    @Test
    fun `Time -- localDate`(): Unit =
        runBlocking {
            Arb.localDate().forAll {
                val ir = IR.Value.Time(it)
                ir.localDate == it && ir.value == it
            }
        }

    @Test
    fun `Time -- offsetTime`(): Unit =
        runBlocking {
            val arb = Arb.bind(Arb.localTime(), Arb.zoneOffset()) { localTime, offset ->
                OffsetTime.of(localTime, offset)
            }
            arb.forAll {
                val ir = IR.Value.Time(it)
                ir.offsetTime == it && ir.value == it
            }
        }

    @Test
    fun `Null`() {
        assertSame(IR.Value.Null, IR.Value.Null)
        assertEquals("NULL", IR.Value.Null.toString())
    }

    @Test
    fun `Object`(): Unit =
        runBlocking {
            val arbFieldValue = Arb.pair(Arb.graphQLFieldName(), Arb.int().map(IR.Value::Number))
            val arbFieldMap = Arb.map(arbFieldValue)
            Arb
                .pair(Arb.graphQLName(), arbFieldMap)
                .forAll { (name, fields) ->
                    val obj = IR.Value.Object(name, fields)
                    obj.name == name && obj.fields == fields
                }
        }

    @Test
    fun `Object -- vararg ctor`() {
        // empty
        assertEquals(
            IR.Value.Object("Obj", emptyMap()),
            IR.Value.Object("Obj")
        )

        assertEquals(
            IR.Value.Object(
                "Obj",
                mapOf("a" to IR.Value.Number(1), "b" to IR.Value.Null)
            ),
            IR.Value.Object(
                "Obj",
                "a" to IR.Value.Number(1),
                "b" to IR.Value.Null
            )
        )
    }

    @Test
    fun `valid for all objects`(): Unit =
        runBlocking {
            val cfg = Config.default + (GenInterfaceStubsIfNeeded to true)
            Arb.graphQLSchema(cfg).forAll(100) { schema ->
                val result = runCatching {
                    DomainValidator(IR, schema).checkAll(100)
                }
                result.isSuccess
            }
        }
}
