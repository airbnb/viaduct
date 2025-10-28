@file:Suppress("ForbiddenImport")

package viaduct.mapping.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ConvTest {
    private val intToString = Conv<Int, String>({ it.toString() }, { it.toInt() })
    private val plusOne = Conv<Int, Int>({ it + 1 }, { it - 1 })

    @Test
    fun `identity`(): Unit =
        runBlocking {
            val conv = Conv.identity<Any>()

            // primitive
            Arb.int().forAll {
                val forwardSame = conv(it) == it
                val invertSame = conv.invert(it) == it
                forwardSame && invertSame
            }

            // non-primitive
            arbitrary { object {} }.forAll {
                val forwardSame = conv(it) === it
                val invertSame = conv.invert(it) === it
                forwardSame && invertSame
            }
        }

    @Test
    fun `identity -- referential equality`() {
        assertSame(Conv.identity<Int>(), Conv.identity<Int>())
    }

    @Test
    fun `identity -- inverse returns self`() {
        val conv = Conv.identity<Any>()
        assertSame(conv, conv.inverse())
    }

    @Test
    fun `Impl -- invoke`(): Unit =
        runBlocking {
            Arb.int().forAll {
                intToString(it) == it.toString()
            }
        }

    @Test
    fun `Impl -- equality`(): Unit =
        runBlocking {
            val forward = { x: Int -> x.toString() }
            val reverse = { x: String -> x.toInt() }
            assertEquals(
                Conv(forward, reverse),
                Conv(forward, reverse)
            )
            assertEquals(
                Conv(forward, reverse),
                Conv(reverse, forward).inverse()
            )

            assertNotEquals(
                Conv(forward, reverse),
                Conv(reverse, forward)
            )
        }

    @Test
    fun `Impl -- invert`(): Unit =
        runBlocking {
            Arb.int().forAll {
                intToString.invert(it.toString()) == it
            }
        }

    @Test
    fun `Impl -- inverse invoke`(): Unit =
        runBlocking {
            Arb.int().forAll {
                intToString.inverse().invoke(it.toString()) == it
            }
        }

    @Test
    fun `Impl -- inverse invert`(): Unit =
        runBlocking {
            Arb.int().forAll {
                intToString.inverse().invert(it) == it.toString()
            }
        }

    @Test
    fun `Impl -- double inverse equals original`() {
        assertEquals(intToString, intToString.inverse().inverse())
    }

    @Test
    fun `Impl -- andThen Conv`(): Unit =
        runBlocking {
            val conv = intToString.andThen(intToString.inverse())
            Arb.int().forAll { conv(it) == it }
        }

    @Test
    fun `AndThen -- equality`(): Unit =
        runBlocking {
            assertEquals(
                plusOne andThen plusOne,
                plusOne andThen plusOne
            )

            assertNotEquals(
                plusOne andThen intToString,
                plusOne andThen plusOne
            )
        }

    @Test
    fun `Impl -- infix andThen Conv`(): Unit =
        runBlocking {
            val conv = intToString andThen intToString.inverse()
            Arb.int().forAll { conv(it) == it }
        }

    @Test
    fun `Impl -- andThen functions`(): Unit =
        runBlocking {
            val conv = intToString.andThen(
                forward = { "_$it" },
                inverse = { it.drop(0) }
            )
            Arb.int().forAll { conv(it) == "_$it" }
        }

    @Test
    fun `Impl -- compose Conv`(): Unit =
        runBlocking {
            val conv = intToString.compose(Conv({ it + 1 }, { it - 1 }))
            Arb.int(Int.MIN_VALUE..(Int.MAX_VALUE - 1)).forAll {
                conv(it) == "${it + 1}"
            }
        }
}
