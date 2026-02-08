@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.mapping.graphql.RawScalar

class ScalarRawValueGenTest {
    private fun ScalarRawValueGen.assertGen(
        typename: String,
        expCls: KClass<*>
    ) {
        val v = this.invoke(typename).let {
            it as? RawScalar ?: fail("Expected RawScalar, got $it")
        }

        assertEquals(typename, v.typename)
        assertEquals(expCls, v.value!!::class)
    }

    @Test
    fun `generates builtin scalars`() {
        val types = mapOf(
            "Int" to Int::class,
            "Float" to Double::class,
            "String" to String::class,
            "Boolean" to Boolean::class,
            "ID" to String::class
        )
        val gen = ScalarRawValueGen(Config.default, RandomSource.default())
        types.forEach { (name, cls) -> gen.assertGen(name, cls) }
    }

    @Test
    fun `generates extended scalars`() {
        val types = mapOf(
            "Date" to LocalDate::class,
            "DateTime" to Instant::class,
            "Long" to Long::class,
            "Short" to Short::class
        )
        val gen = ScalarRawValueGen(Config.default, RandomSource.default())
        types.forEach { (name, cls) -> gen.assertGen(name, cls) }
    }

    @Test
    fun `generates configured scalars`() {
        val extras = mapOf(
            "Foo" to Arb.int() to Int::class,
            "Bar" to Arb.string() to String::class,
            "Baz" to Arb.constant(null) to null
        )

        val cfg = Config.default + (ScalarValueOverrides to extras.keys.toMap())
        val gen = ScalarRawValueGen(cfg, RandomSource.default())

        extras.forEach { (pair, cls) ->
            val result = gen(pair.first) as RawScalar
            assertEquals(pair.first, result.typename)

            if (cls == null) {
                assertNull(result.value)
            } else {
                assertEquals(cls, result.value!!::class)
            }
        }
    }

    @Test
    fun `DateTime can be converted to millis`(): Unit =
        runBlocking {
            val instants = arbitrary { rs ->
                val gen = ScalarRawValueGen(Config.default, rs)
                val rawScalar = gen("DateTime") as RawScalar
                rawScalar.value as Instant
            }

            instants.forAll { instant ->
                runCatching {
                    instant.toEpochMilli()
                }.isSuccess
            }
        }

    @Test
    fun `Date can be converted to millis`(): Unit =
        runBlocking {
            val dates = arbitrary { rs ->
                val gen = ScalarRawValueGen(Config.default, rs)
                val rawScalar = gen("Date") as RawScalar
                rawScalar.value as LocalDate
            }

            dates.forAll { date ->
                runCatching {
                    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }.isSuccess
            }
        }
}
