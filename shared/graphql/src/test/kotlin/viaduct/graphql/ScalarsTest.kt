package viaduct.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.BooleanValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
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
        fun `scalar has correct name`() {
            assertEquals(ExtendedScalars.DateTime.name, Scalars.DateTimeScalar.name)
        }
    }

    @Nested
    inner class GraphQLLongTest {
        private val coercing = Scalars.GraphQLLong.coercing

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
            fun `parseLiteral unsupported type throws exception`() {
                val boolValue = BooleanValue.newBooleanValue(true).build()
                val exception = assertThrows(CoercingParseLiteralException::class.java) {
                    coercing.parseLiteral(boolValue, coercedVariables, ctx, locale)
                }
                assertTrue(exception.message!!.contains("Expected AST type 'IntValue' or 'StringValue'"))
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
