package viaduct.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ScalarsTest {
    private val ctx = GraphQLContext.newContext().build()
    private val locale = Locale.getDefault()
    private val coercedVariables = CoercedVariables.emptyVariables()

    @Nested
    inner class DateTimeScalarTest {
        private val coercing = Scalars.DateTimeScalar.coercing

        @Test
        fun `serialize with Instant converts to OffsetDateTime format`() {
            val instant = Instant.parse("2023-06-15T10:30:00Z")
            val result = coercing.serialize(instant, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `serialize with OffsetDateTime delegates to ExtendedScalars`() {
            val offsetDateTime = OffsetDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC)
            val result = coercing.serialize(offsetDateTime, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `serialize with String delegates to ExtendedScalars`() {
            val dateTimeString = "2023-06-15T10:30:00Z"
            val result = coercing.serialize(dateTimeString, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `parseValue with Instant converts to OffsetDateTime format`() {
            val instant = Instant.parse("2023-06-15T10:30:00Z")
            val result = coercing.parseValue(instant, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `parseValue with OffsetDateTime delegates to ExtendedScalars`() {
            val offsetDateTime = OffsetDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC)
            val result = coercing.parseValue(offsetDateTime, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `parseValue with String delegates to ExtendedScalars`() {
            val dateTimeString = "2023-06-15T10:30:00Z"
            val result = coercing.parseValue(dateTimeString, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `parseLiteral delegates to ExtendedScalars`() {
            val stringValue = StringValue.newStringValue("2023-06-15T10:30:00Z").build()
            val result = coercing.parseLiteral(stringValue, coercedVariables, ctx, locale)
            assertNotNull(result)
        }

        @Test
        fun `valueToLiteral with OffsetDateTime returns StringValue`() {
            val offsetDateTime = OffsetDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC)
            val result = coercing.valueToLiteral(offsetDateTime, ctx, locale)
            assertTrue(result is StringValue)
            assertNotNull((result as StringValue).value)
        }

        @Test
        fun `valueToLiteral with Instant returns StringValue`() {
            val instant = Instant.parse("2023-06-15T10:30:00Z")
            val result = coercing.valueToLiteral(instant, ctx, locale)
            assertTrue(result is StringValue)
            assertNotNull((result as StringValue).value)
        }

        @Test
        fun `valueToLiteral with null ctx and locale does not throw`() {
            // ValuesResolver#valueToLiteral (called from DelegatedSelectionsForCacheKeyVisitor)
            // passes null for ctx and locale, so this must not NPE.
            val offsetDateTime = OffsetDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC)
            val result = coercing.valueToLiteral(offsetDateTime, null, null)
            assertTrue(result is StringValue)
            assertNotNull((result as StringValue).value)
        }

        @Test
        fun `scalar has correct name`() {
            assertEquals(ExtendedScalars.DateTime.name, Scalars.DateTimeScalar.name)
        }
    }

    @Nested
    inner class GraphQLLongTest {
        private val coercing = Scalars.GraphQLLong.coercing
        private val validInputs =
            listOf(
                "42" to 42L,
                "42.0000" to 42L,
                42.0000 to 42L,
                42 to 42L,
                "-1" to -1L,
                BigInteger.valueOf(42) to 42L,
                BigDecimal("42") to 42L,
                42.0f to 42L,
                42.toByte() to 42L,
                42.toShort() to 42L,
                AtomicInteger(42) to 42L,
                12345678910L to 12345678910L,
                Long.MAX_VALUE to Long.MAX_VALUE,
                Long.MIN_VALUE to Long.MIN_VALUE,
                42345784398534785L to 42345784398534785L
            )
        private val invalidInputs: List<Any> =
            listOf(
                "",
                "not a number ",
                "42.3",
                42.3,
                42.3f,
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
                Any()
            )

        @Nested
        inner class SerializeTest {
            @Test
            fun `serialize Long returns string representation`() {
                val result = coercing.serialize(12345L, ctx, locale)
                assertEquals("12345", result)
            }

            @Test
            fun `serialize Int returns string representation`() {
                val result = coercing.serialize(12345, ctx, locale)
                assertEquals("12345", result)
            }

            @Test
            fun `serialize String number returns string representation`() {
                val result = coercing.serialize("12345", ctx, locale)
                assertEquals("12345", result)
            }

            @Test
            fun `serialize Double returns string representation`() {
                val result = coercing.serialize(12345.0, ctx, locale)
                assertEquals("12345", result)
            }

            @Test
            fun `serialize invalid type throws CoercingSerializeException`() {
                val exception = assertThrows(CoercingSerializeException::class.java) {
                    coercing.serialize(listOf(1, 2, 3), ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected type 'Long'"))
            }

            @Test
            fun `serialize non-numeric string throws CoercingSerializeException`() {
                val exception = assertThrows(CoercingSerializeException::class.java) {
                    coercing.serialize("not-a-number", ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected type 'Long'"))
            }

            @Test
            fun `serialize decimal with fractional part throws CoercingSerializeException`() {
                val exception = assertThrows(CoercingSerializeException::class.java) {
                    coercing.serialize(12345.67, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected type 'Long'"))
            }

            @Test
            fun `serialize supported Long values to strings`() {
                validInputs.forEach { (input, expected) ->
                    assertEquals(expected.toString(), coercing.serialize(input, ctx, locale))
                }
            }

            @Test
            fun `serialize rejects unsupported Long values`() {
                invalidInputs.forEach { input ->
                    val exception = assertThrows(CoercingSerializeException::class.java) {
                        coercing.serialize(input, ctx, locale)
                    }
                    assertTrue(exception.message!!.contains("Expected type 'Long'"))
                }
            }
        }

        @Nested
        inner class ParseValueTest {
            @Test
            fun `parseValue Long returns same value`() {
                val result = coercing.parseValue(12345L, ctx, locale)
                assertEquals(12345L, result)
            }

            @Test
            fun `parseValue Int returns Long`() {
                val result = coercing.parseValue(12345, ctx, locale)
                assertEquals(12345L, result)
            }

            @Test
            fun `parseValue String returns Long`() {
                val result = coercing.parseValue("12345", ctx, locale)
                assertEquals(12345L, result)
            }

            @Test
            fun `parseValue invalid type throws CoercingParseValueException`() {
                val exception = assertThrows(CoercingParseValueException::class.java) {
                    coercing.parseValue(listOf(1, 2, 3), ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected type 'Long'"))
            }

            @Test
            fun `parseValue non-numeric string throws CoercingParseValueException`() {
                val exception = assertThrows(CoercingParseValueException::class.java) {
                    coercing.parseValue("not-a-number", ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected type 'Long'"))
            }

            @Test
            fun `parseValue supports standard Long values`() {
                validInputs.forEach { (input, expected) ->
                    assertEquals(expected, coercing.parseValue(input, ctx, locale))
                }
            }

            @Test
            fun `parseValue rejects unsupported Long values`() {
                invalidInputs.forEach { input ->
                    val exception = assertThrows(CoercingParseValueException::class.java) {
                        coercing.parseValue(input, ctx, locale)
                    }
                    assertTrue(exception.message!!.contains("Expected type 'Long'"))
                }
            }
        }

        @Nested
        inner class ParseLiteralTest {
            @Test
            fun `parseLiteral StringValue returns Long`() {
                val stringValue = StringValue.newStringValue("12345").build()
                val result = coercing.parseLiteral(stringValue, coercedVariables, ctx, locale)
                assertEquals(12345L, result)
            }

            @Test
            fun `parseLiteral negative StringValue returns Long`() {
                val stringValue = StringValue.newStringValue("-1").build()
                val result = coercing.parseLiteral(stringValue, coercedVariables, ctx, locale)
                assertEquals(-1L, result)
            }

            @Test
            fun `parseLiteral IntValue returns Long`() {
                val intValue = IntValue.newIntValue(BigInteger.valueOf(12345)).build()
                val result = coercing.parseLiteral(intValue, coercedVariables, ctx, locale)
                assertEquals(12345L, result)
            }

            @Test
            fun `parseLiteral IntValue at Long MAX_VALUE returns correct value`() {
                val intValue = IntValue.newIntValue(BigInteger.valueOf(Long.MAX_VALUE)).build()
                val result = coercing.parseLiteral(intValue, coercedVariables, ctx, locale)
                assertEquals(Long.MAX_VALUE, result)
            }

            @Test
            fun `parseLiteral IntValue at Long MIN_VALUE returns correct value`() {
                val intValue = IntValue.newIntValue(BigInteger.valueOf(Long.MIN_VALUE)).build()
                val result = coercing.parseLiteral(intValue, coercedVariables, ctx, locale)
                assertEquals(Long.MIN_VALUE, result)
            }

            @Test
            fun `parseLiteral IntValue exceeding Long MAX_VALUE throws exception`() {
                val bigValue = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
                val intValue = IntValue.newIntValue(bigValue).build()
                val exception = assertThrows(CoercingParseLiteralException::class.java) {
                    coercing.parseLiteral(intValue, coercedVariables, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected value to be in the Long range"))
            }

            @Test
            fun `parseLiteral IntValue below Long MIN_VALUE throws exception`() {
                val smallValue = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)
                val intValue = IntValue.newIntValue(smallValue).build()
                val exception = assertThrows(CoercingParseLiteralException::class.java) {
                    coercing.parseLiteral(intValue, coercedVariables, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected value to be in the Long range"))
            }

            @Test
            fun `parseLiteral invalid StringValue throws exception`() {
                val stringValue = StringValue.newStringValue("not-a-number").build()
                val exception = assertThrows(CoercingParseLiteralException::class.java) {
                    coercing.parseLiteral(stringValue, coercedVariables, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected value to be a Long"))
            }

            @Test
            fun `parseLiteral FloatValue throws exception`() {
                val floatValue = FloatValue.newFloatValue(BigDecimal("42.3")).build()
                val exception = assertThrows(CoercingParseLiteralException::class.java) {
                    coercing.parseLiteral(floatValue, coercedVariables, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected AST type 'IntValue' or 'StringValue'"))
            }

            @Test
            fun `parseLiteral unsupported type throws exception`() {
                val boolValue = BooleanValue.newBooleanValue(true).build()
                val exception = assertThrows(CoercingParseLiteralException::class.java) {
                    coercing.parseLiteral(boolValue, coercedVariables, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected AST type 'IntValue' or 'StringValue'"))
            }
        }

        @Nested
        inner class ValueToLiteralTest {
            @Test
            fun `valueToLiteral returns IntValue`() {
                val result = coercing.valueToLiteral(Long.MIN_VALUE, ctx, locale)

                assertTrue(result is IntValue)
                assertEquals(BigInteger.valueOf(Long.MIN_VALUE), (result as IntValue).value)
            }

            @Test
            fun `valueToLiteral accepts values supported by serialize`() {
                validInputs.forEach { (input, expected) ->
                    val result = coercing.valueToLiteral(input, ctx, locale)

                    assertTrue(result is IntValue)
                    assertEquals(BigInteger.valueOf(expected), (result as IntValue).value)
                }
            }

            @Test
            fun `valueToLiteral accepts null context and locale`() {
                val result = coercing.valueToLiteral(Long.MAX_VALUE, null, null)

                assertTrue(result is IntValue)
                assertEquals(BigInteger.valueOf(Long.MAX_VALUE), (result as IntValue).value)
            }

            @Test
            fun `valueToLiteral rejects invalid values`() {
                invalidInputs.forEach { input ->
                    val exception = assertThrows(CoercingSerializeException::class.java) {
                        coercing.valueToLiteral(input, ctx, locale)
                    }
                    assertTrue(exception.message!!.contains("Expected type 'Long'"))
                }
            }
        }

        @Test
        fun `scalar has correct name`() {
            assertEquals("Long", Scalars.GraphQLLong.name)
        }

        @Test
        fun `scalar has correct description`() {
            val description = Scalars.GraphQLLong.description
            assertNotNull(description)
            assertTrue(description!!.contains("Long"))
            assertTrue(description.contains("string"))
        }
    }

    @Nested
    inner class BackingDataTest {
        private val coercing = Scalars.BackingData.coercing

        @Test
        fun `serialize throws exception`() {
            val exception = assertThrows(Exception::class.java) {
                coercing.serialize("any", ctx, locale)
            }
            assertTrue(exception.message!!.contains("serialize should not be called for BackingData scalar type"))
        }

        @Test
        fun `parseValue throws exception`() {
            val exception = assertThrows(Exception::class.java) {
                coercing.parseValue("any", ctx, locale)
            }
            assertTrue(exception.message!!.contains("parseValue should not be called for BackingData scalar type"))
        }

        @Test
        fun `parseLiteral throws exception`() {
            val stringValue = StringValue.newStringValue("any").build()
            val exception = assertThrows(Exception::class.java) {
                coercing.parseLiteral(stringValue, coercedVariables, ctx, locale)
            }
            assertTrue(exception.message!!.contains("parseLiteral should not be called for BackingData scalar type"))
        }

        @Test
        fun `scalar has correct name`() {
            assertEquals("BackingData", Scalars.BackingData.name)
        }
    }

    @Nested
    inner class ViaductStandardScalarsTest {
        @Test
        fun `contains expected scalars`() {
            val scalars = Scalars.viaductStandardScalars
            assertTrue(scalars.contains(ExtendedScalars.Date))
            assertTrue(scalars.contains(ExtendedScalars.GraphQLByte))
            assertTrue(scalars.contains(ExtendedScalars.GraphQLShort))
            assertTrue(scalars.contains(ExtendedScalars.Json))
            assertTrue(scalars.contains(ExtendedScalars.Time))
            assertTrue(scalars.contains(Scalars.BackingData))
            assertTrue(scalars.contains(Scalars.DateTimeScalar))
            assertTrue(scalars.contains(Scalars.GraphQLLong))
        }

        @Test
        fun `has correct size`() {
            assertEquals(8, Scalars.viaductStandardScalars.size)
        }
    }
}
