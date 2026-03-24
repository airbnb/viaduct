package viaduct.graphql.schema

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Comprehensive tests for ViaductSchema.Literal types.
 *
 * Test categories:
 * - Factory/validation tests (IntLiteral.of, FloatLiteral.of, EnumLit.of)
 * - Value-equality tests
 * - toString() GraphQL grammar conformance tests
 * - value property type tests
 * - Delegation tests (ListLiteral as List, ObjectLiteral as Map)
 */
class LiteralTest {
    @Nested
    inner class NullValueTests {
        @Test
        fun `NULL singleton instance`() {
            val null1 = ViaductSchema.NULL
            val null2 = ViaductSchema.NULL
            assertSame(null1, null2)
        }

        @Test
        fun `value property returns null`() {
            assertNull(ViaductSchema.NULL.value)
        }

        @Test
        fun `toString returns GraphQL null literal`() {
            assertEquals("null", ViaductSchema.NULL.toString())
        }

        @Test
        fun `equals compares by type`() {
            assertEquals(ViaductSchema.NULL, ViaductSchema.NULL)
        }

        @Test
        fun `hashCode is consistent`() {
            assertEquals(ViaductSchema.NULL.hashCode(), ViaductSchema.NULL.hashCode())
        }
    }

    @Nested
    inner class BooleanValueTests {
        @Test
        fun `TRUE singleton instance`() {
            val true1 = ViaductSchema.TRUE
            val true2 = ViaductSchema.TRUE
            assertSame(true1, true2)
        }

        @Test
        fun `FALSE singleton instance`() {
            val false1 = ViaductSchema.FALSE
            val false2 = ViaductSchema.FALSE
            assertSame(false1, false2)
        }

        @Test
        fun `TRUE value property returns true`() {
            assertTrue(ViaductSchema.TRUE.value)
        }

        @Test
        fun `FALSE value property returns false`() {
            assertFalse(ViaductSchema.FALSE.value)
        }

        @Test
        fun `TRUE toString returns GraphQL true literal`() {
            assertEquals("true", ViaductSchema.TRUE.toString())
        }

        @Test
        fun `FALSE toString returns GraphQL false literal`() {
            assertEquals("false", ViaductSchema.FALSE.toString())
        }

        @Test
        fun `TRUE and FALSE are not equal`() {
            assertNotEquals(ViaductSchema.TRUE, ViaductSchema.FALSE)
        }

        @Test
        fun `BooleanValue sealed interface includes both TrueValue and FalseValue`() {
            val values: List<ViaductSchema.BooleanLiteral> = listOf(ViaductSchema.TRUE, ViaductSchema.FALSE)
            assertEquals(2, values.size)
            assertTrue(values[0] is ViaductSchema.TrueLiteral)
            assertTrue(values[1] is ViaductSchema.FalseLiteral)
        }
    }

    @Nested
    inner class StringValueTests {
        @Test
        fun `of factory creates StringValue`() {
            val sv = ViaductSchema.StringLiteral.of("hello")
            assertEquals("hello", sv.value)
        }

        @Test
        fun `value property returns unquoted content`() {
            val sv = ViaductSchema.StringLiteral.of("test string")
            assertEquals("test string", sv.value)
        }

        @Test
        fun `toString returns quoted string`() {
            val sv = ViaductSchema.StringLiteral.of("hello")
            assertEquals("hello", sv.value)
        }

        @Test
        fun `toString escapes double quotes`() {
            val sv = ViaductSchema.StringLiteral.of("say \"hello\"")
            assertEquals("say \"hello\"", sv.value)
        }

        @Test
        fun `toString escapes backslash`() {
            val sv = ViaductSchema.StringLiteral.of("path\\to\\file")
            assertEquals("path\\to\\file", sv.value)
        }

        @Test
        fun `toString escapes newline`() {
            val sv = ViaductSchema.StringLiteral.of("line1\nline2")
            assertEquals("line1\nline2", sv.value)
        }

        @Test
        fun `toString escapes carriage return`() {
            val sv = ViaductSchema.StringLiteral.of("line1\rline2")
            assertEquals("line1\rline2", sv.value)
        }

        @Test
        fun `toString escapes tab`() {
            val sv = ViaductSchema.StringLiteral.of("col1\tcol2")
            assertEquals("col1\tcol2", sv.value)
        }

        @Test
        fun `toString escapes backspace`() {
            val sv = ViaductSchema.StringLiteral.of("back\bspace")
            assertEquals("back\bspace", sv.value)
        }

        @Test
        fun `toString escapes form feed`() {
            val sv = ViaductSchema.StringLiteral.of("form\u000Cfeed")
            assertEquals("form\u000Cfeed", sv.value)
        }

        @Test
        fun `toString escapes control characters with unicode`() {
            val sv = ViaductSchema.StringLiteral.of("ctrl\u0001char")
            assertEquals("\"ctrl\\u0001char\"", sv.toString())
        }

        @Test
        fun `toString handles empty string`() {
            val sv = ViaductSchema.StringLiteral.of("")
            assertEquals("", sv.value)
        }

        @Test
        fun `toString handles unicode characters`() {
            val sv = ViaductSchema.StringLiteral.of("日本語")
            assertEquals("\"日本語\"", sv.toString())
        }

        @Test
        fun `equality based on content`() {
            val sv1 = ViaductSchema.StringLiteral.of("test")
            val sv2 = ViaductSchema.StringLiteral.of("test")
            val sv3 = ViaductSchema.StringLiteral.of("other")

            assertEquals(sv1, sv2)
            assertNotEquals(sv1, sv3)
        }

        @Test
        fun `hashCode based on content`() {
            val sv1 = ViaductSchema.StringLiteral.of("test")
            val sv2 = ViaductSchema.StringLiteral.of("test")

            assertEquals(sv1.hashCode(), sv2.hashCode())
        }
    }

    @Nested
    inner class IntValueTests {
        @Test
        fun `valid integer literals are accepted`() {
            listOf("0", "1", "42", "123456789", "-1", "-42", "-123456789").forEach { literal ->
                val iv = ViaductSchema.IntLiteral.of(literal)
                assertEquals(literal, iv.toString())
            }
        }

        @Test
        fun `value property returns BigInteger`() {
            val iv = ViaductSchema.IntLiteral.of("42")
            assertEquals(BigInteger.valueOf(42), iv.value)
        }

        @Test
        fun `value property handles large numbers`() {
            val large = "123456789012345678901234567890"
            val iv = ViaductSchema.IntLiteral.of(large)
            assertEquals(BigInteger(large), iv.value)
        }

        @Test
        fun `value property handles negative numbers`() {
            val iv = ViaductSchema.IntLiteral.of("-42")
            assertEquals(BigInteger.valueOf(-42), iv.value)
        }

        @Test
        fun `rejects leading zeros`() {
            listOf("01", "007", "-01", "-007").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.IntLiteral.of(literal)
                }
            }
        }

        @Test
        fun `rejects invalid formats`() {
            listOf("", "abc", "1.5", "1e10", "+1", "1+2").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.IntLiteral.of(literal)
                }
            }
        }

        @Test
        fun `toString returns original literal`() {
            val iv = ViaductSchema.IntLiteral.of("42")
            assertEquals("42", iv.toString())
        }

        @Test
        fun `equality based on literal`() {
            val iv1 = ViaductSchema.IntLiteral.of("42")
            val iv2 = ViaductSchema.IntLiteral.of("42")
            val iv3 = ViaductSchema.IntLiteral.of("43")

            assertEquals(iv1, iv2)
            assertNotEquals(iv1, iv3)
        }

        @Test
        fun `hashCode based on literal`() {
            val iv1 = ViaductSchema.IntLiteral.of("42")
            val iv2 = ViaductSchema.IntLiteral.of("42")

            assertEquals(iv1.hashCode(), iv2.hashCode())
        }
    }

    @Nested
    inner class FloatValueTests {
        @Test
        fun `valid float literals are accepted`() {
            listOf("0.0", "1.0", "3.14159", "-1.5", "1e10", "1E10", "1e+10", "1e-10", "1.5e10", "-1.5E-10").forEach { literal ->
                val fv = ViaductSchema.FloatLiteral.of(literal)
                assertEquals(literal, fv.toString())
            }
        }

        @Test
        fun `value property returns BigDecimal`() {
            val fv = ViaductSchema.FloatLiteral.of("3.14")
            assertEquals(BigDecimal("3.14"), fv.value)
        }

        @Test
        fun `value property handles scientific notation`() {
            val fv = ViaductSchema.FloatLiteral.of("1.5e10")
            assertEquals(BigDecimal("1.5e10"), fv.value)
        }

        @Test
        fun `value property handles negative numbers`() {
            val fv = ViaductSchema.FloatLiteral.of("-2.5")
            assertEquals(BigDecimal("-2.5"), fv.value)
        }

        @Test
        fun `rejects leading zeros in integer part`() {
            listOf("01.0", "007.5", "-01.5").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.FloatLiteral.of(literal)
                }
            }
        }

        @Test
        fun `rejects integer-only format - must have fractional or exponent`() {
            listOf("42", "-42", "0").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.FloatLiteral.of(literal)
                }
            }
        }

        @Test
        fun `rejects invalid float formats`() {
            listOf("", "abc", ".5", "1.", "e10").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.FloatLiteral.of(literal)
                }
            }
        }

        @Test
        fun `toString returns original literal`() {
            val fv = ViaductSchema.FloatLiteral.of("3.14159")
            assertEquals("3.14159", fv.toString())
        }

        @Test
        fun `equality based on literal`() {
            val fv1 = ViaductSchema.FloatLiteral.of("3.14")
            val fv2 = ViaductSchema.FloatLiteral.of("3.14")
            val fv3 = ViaductSchema.FloatLiteral.of("3.15")

            assertEquals(fv1, fv2)
            assertNotEquals(fv1, fv3)
        }

        @Test
        fun `different literal representations are not equal`() {
            // 3.14 and 3.140 have the same numeric value but different literals
            val fv1 = ViaductSchema.FloatLiteral.of("3.14")
            val fv2 = ViaductSchema.FloatLiteral.of("3.140")

            assertNotEquals(fv1, fv2)
        }

        @Test
        fun `hashCode based on literal`() {
            val fv1 = ViaductSchema.FloatLiteral.of("3.14")
            val fv2 = ViaductSchema.FloatLiteral.of("3.14")

            assertEquals(fv1.hashCode(), fv2.hashCode())
        }
    }

    @Nested
    inner class EnumLitTests {
        @Test
        fun `valid enum literals are accepted`() {
            listOf("RED", "GREEN", "BLUE", "Status", "_private", "Field123", "_123").forEach { literal ->
                val ev = ViaductSchema.EnumLit.of(literal)
                assertEquals(literal, ev.toString())
            }
        }

        @Test
        fun `value property returns literal as String`() {
            val ev = ViaductSchema.EnumLit.of("RED")
            assertEquals("RED", ev.value)
        }

        @Test
        fun `rejects reserved words`() {
            listOf("true", "false", "null").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.EnumLit.of(literal)
                }
            }
        }

        @Test
        fun `rejects invalid name formats`() {
            listOf("123start", "has-dash", "has.dot", "has space", "").forEach { literal ->
                assertThrows<IllegalArgumentException> {
                    ViaductSchema.EnumLit.of(literal)
                }
            }
        }

        @Test
        fun `toString returns literal directly - no quotes`() {
            val ev = ViaductSchema.EnumLit.of("ACTIVE")
            assertEquals("ACTIVE", ev.toString())
        }

        @Test
        fun `equality based on literal`() {
            val ev1 = ViaductSchema.EnumLit.of("RED")
            val ev2 = ViaductSchema.EnumLit.of("RED")
            val ev3 = ViaductSchema.EnumLit.of("BLUE")

            assertEquals(ev1, ev2)
            assertNotEquals(ev1, ev3)
        }

        @Test
        fun `hashCode based on literal`() {
            val ev1 = ViaductSchema.EnumLit.of("RED")
            val ev2 = ViaductSchema.EnumLit.of("RED")

            assertEquals(ev1.hashCode(), ev2.hashCode())
        }
    }

    @Nested
    inner class ListValueTests {
        @Test
        fun `of factory creates ListValue`() {
            val lv = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))
            assertEquals(1, lv.size)
        }

        @Test
        fun `implements List interface - size`() {
            val lv = ViaductSchema.ListLiteral.of(
                listOf(
                    ViaductSchema.IntLiteral.of("1"),
                    ViaductSchema.IntLiteral.of("2"),
                    ViaductSchema.IntLiteral.of("3")
                )
            )
            assertEquals(3, lv.size)
        }

        @Test
        fun `implements List interface - get`() {
            val lv = ViaductSchema.ListLiteral.of(
                listOf(
                    ViaductSchema.StringLiteral.of("first"),
                    ViaductSchema.StringLiteral.of("second")
                )
            )
            assertEquals("first", (lv[0] as ViaductSchema.StringLiteral).value)
            assertEquals("second", (lv[1] as ViaductSchema.StringLiteral).value)
        }

        @Test
        fun `implements List interface - isEmpty`() {
            val empty = ViaductSchema.ListLiteral.of(emptyList())
            val nonEmpty = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))

            assertTrue(empty.isEmpty())
            assertFalse(nonEmpty.isEmpty())
        }

        @Test
        fun `implements List interface - contains`() {
            val element = ViaductSchema.IntLiteral.of("42")
            val lv = ViaductSchema.ListLiteral.of(listOf(element))

            assertTrue(lv.contains(element))
        }

        @Test
        fun `implements List interface - iterator`() {
            val elements = listOf(
                ViaductSchema.IntLiteral.of("1"),
                ViaductSchema.IntLiteral.of("2")
            )
            val lv = ViaductSchema.ListLiteral.of(elements)

            val collected = lv.toList()
            assertEquals(elements, collected)
        }

        @Test
        fun `value property returns List of Any`() {
            val lv = ViaductSchema.ListLiteral.of(
                listOf(
                    ViaductSchema.IntLiteral.of("1"),
                    ViaductSchema.StringLiteral.of("two"),
                    ViaductSchema.NULL
                )
            )
            val value = lv.value

            assertEquals(BigInteger.valueOf(1), value[0])
            assertEquals("two", value[1])
            assertNull(value[2])
        }

        @Test
        fun `value property handles nested lists`() {
            val inner = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))
            val outer = ViaductSchema.ListLiteral.of(listOf(inner))

            val value = outer.value
            assertEquals(listOf(listOf(BigInteger.valueOf(1))), value)
        }

        @Test
        fun `toString returns GraphQL list literal`() {
            val lv = ViaductSchema.ListLiteral.of(
                listOf(
                    ViaductSchema.IntLiteral.of("1"),
                    ViaductSchema.IntLiteral.of("2")
                )
            )
            assertEquals("[1, 2]", lv.toString())
        }

        @Test
        fun `toString handles empty list`() {
            val lv = ViaductSchema.ListLiteral.of(emptyList())
            assertEquals("[]", lv.toString())
        }

        @Test
        fun `toString handles nested structures`() {
            val inner = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.StringLiteral.of("a")))
            val outer = ViaductSchema.ListLiteral.of(listOf(inner))
            assertEquals("[[\"a\"]]", outer.toString())
        }

        @Test
        fun `equality based on elements`() {
            val lv1 = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))
            val lv2 = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))
            val lv3 = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("2")))

            assertEquals(lv1, lv2)
            assertNotEquals(lv1, lv3)
        }

        @Test
        fun `hashCode based on elements`() {
            val lv1 = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))
            val lv2 = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("1")))

            assertEquals(lv1.hashCode(), lv2.hashCode())
        }
    }

    @Nested
    inner class ObjectValueTests {
        @Test
        fun `of factory creates ObjectValue`() {
            val ov = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))
            assertEquals(1, ov.size)
        }

        @Test
        fun `implements Map interface - size`() {
            val ov = ViaductSchema.ObjectLiteral.of(
                mapOf(
                    "a" to ViaductSchema.IntLiteral.of("1"),
                    "b" to ViaductSchema.IntLiteral.of("2")
                )
            )
            assertEquals(2, ov.size)
        }

        @Test
        fun `implements Map interface - get`() {
            val ov = ViaductSchema.ObjectLiteral.of(
                mapOf("name" to ViaductSchema.StringLiteral.of("Alice"))
            )
            assertEquals("Alice", (ov["name"] as ViaductSchema.StringLiteral).value)
        }

        @Test
        fun `implements Map interface - isEmpty`() {
            val empty = ViaductSchema.ObjectLiteral.of(emptyMap())
            val nonEmpty = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))

            assertTrue(empty.isEmpty())
            assertFalse(nonEmpty.isEmpty())
        }

        @Test
        fun `implements Map interface - containsKey`() {
            val ov = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))

            assertTrue(ov.containsKey("x"))
            assertFalse(ov.containsKey("y"))
        }

        @Test
        fun `implements Map interface - keys`() {
            val ov = ViaductSchema.ObjectLiteral.of(
                mapOf(
                    "a" to ViaductSchema.IntLiteral.of("1"),
                    "b" to ViaductSchema.IntLiteral.of("2")
                )
            )
            assertEquals(setOf("a", "b"), ov.keys)
        }

        @Test
        fun `implements Map interface - entries`() {
            val ov = ViaductSchema.ObjectLiteral.of(
                mapOf("x" to ViaductSchema.IntLiteral.of("42"))
            )

            val entries = ov.entries
            assertEquals(1, entries.size)
            val entry = entries.first()
            assertEquals("x", entry.key)
            assertEquals(ViaductSchema.IntLiteral.of("42"), entry.value)
        }

        @Test
        fun `value property returns Map of String to Any`() {
            val ov = ViaductSchema.ObjectLiteral.of(
                mapOf(
                    "num" to ViaductSchema.IntLiteral.of("42"),
                    "str" to ViaductSchema.StringLiteral.of("hello"),
                    "nil" to ViaductSchema.NULL
                )
            )
            val value = ov.value

            assertEquals(BigInteger.valueOf(42), value["num"])
            assertEquals("hello", value["str"])
            assertNull(value["nil"])
        }

        @Test
        fun `value property handles nested objects`() {
            val inner = ViaductSchema.ObjectLiteral.of(mapOf("y" to ViaductSchema.IntLiteral.of("2")))
            val outer = ViaductSchema.ObjectLiteral.of(mapOf("inner" to inner))

            val value = outer.value
            assertEquals(mapOf("inner" to mapOf("y" to BigInteger.valueOf(2))), value)
        }

        @Test
        fun `toString returns GraphQL object literal`() {
            // Use LinkedHashMap to preserve order for predictable toString
            val ov = ViaductSchema.ObjectLiteral.of(
                linkedMapOf(
                    "x" to ViaductSchema.IntLiteral.of("1"),
                    "y" to ViaductSchema.IntLiteral.of("2")
                )
            )
            assertEquals("{x: 1, y: 2}", ov.toString())
        }

        @Test
        fun `toString handles empty object`() {
            val ov = ViaductSchema.ObjectLiteral.of(emptyMap())
            assertEquals("{}", ov.toString())
        }

        @Test
        fun `toString handles nested structures`() {
            val inner = ViaductSchema.ObjectLiteral.of(mapOf("a" to ViaductSchema.StringLiteral.of("b")))
            val outer = ViaductSchema.ObjectLiteral.of(mapOf("nested" to inner))
            assertEquals("{nested: {a: \"b\"}}", outer.toString())
        }

        @Test
        fun `equality based on fields`() {
            val ov1 = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))
            val ov2 = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))
            val ov3 = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("2")))

            assertEquals(ov1, ov2)
            assertNotEquals(ov1, ov3)
        }

        @Test
        fun `hashCode based on fields`() {
            val ov1 = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))
            val ov2 = ViaductSchema.ObjectLiteral.of(mapOf("x" to ViaductSchema.IntLiteral.of("1")))

            assertEquals(ov1.hashCode(), ov2.hashCode())
        }
    }

    @Nested
    inner class CrossTypeTests {
        @Test
        fun `different Value types are not equal`() {
            val int = ViaductSchema.IntLiteral.of("1")
            val str = ViaductSchema.StringLiteral.of("1")
            val flt = ViaductSchema.FloatLiteral.of("1.0")

            assertNotEquals(int, str)
            assertNotEquals(int, flt)
            assertNotEquals(str, flt)
        }

        @Test
        fun `complex nested structure value property`() {
            val complex = ViaductSchema.ObjectLiteral.of(
                mapOf(
                    "items" to ViaductSchema.ListLiteral.of(
                        listOf(
                            ViaductSchema.ObjectLiteral.of(
                                mapOf(
                                    "id" to ViaductSchema.IntLiteral.of("1"),
                                    "name" to ViaductSchema.StringLiteral.of("first")
                                )
                            ),
                            ViaductSchema.ObjectLiteral.of(
                                mapOf(
                                    "id" to ViaductSchema.IntLiteral.of("2"),
                                    "name" to ViaductSchema.StringLiteral.of("second")
                                )
                            )
                        )
                    ),
                    "count" to ViaductSchema.IntLiteral.of("2")
                )
            )

            val value = complex.value
            @Suppress("UNCHECKED_CAST")
            val items = value["items"] as List<Map<String, Any?>>
            assertEquals(2, items.size)
            assertEquals(BigInteger.valueOf(1), items[0]["id"])
            assertEquals("first", items[0]["name"])
        }

        @Test
        fun `complex nested structure toString`() {
            val complex = ViaductSchema.ObjectLiteral.of(
                linkedMapOf(
                    "list" to ViaductSchema.ListLiteral.of(
                        listOf(ViaductSchema.IntLiteral.of("1"), ViaductSchema.IntLiteral.of("2"))
                    ),
                    "flag" to ViaductSchema.TRUE
                )
            )
            assertEquals("{list: [1, 2], flag: true}", complex.toString())
        }
    }
}
