@file:Suppress("ForbiddenImport")

package viaduct.utils.bijection

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.utils.collections.Some

class BijectionTest {
    private val intToString = Bijection<Int, String>({ it.toString() }, { it.toInt() })
    private val plusOne = Bijection<Int, Int>({ it + 1 }, { it - 1 })

    @Test
    fun `identity`(): Unit =
        runBlocking {
            val bij = Bijection.identity<Any>()

            // primitive
            Arb.int().forAll {
                val forwardSame = bij(it) == it
                val invertSame = bij.invert(it) == it
                forwardSame && invertSame
            }

            // non-primitive
            arbitrary { object {} }.forAll {
                val forwardSame = bij(it) === it
                val invertSame = bij.invert(it) === it
                forwardSame && invertSame
            }
        }

    @Test
    fun `identity -- referential equality`() {
        assertSame(Bijection.identity<Int>(), Bijection.identity<Int>())
    }

    @Test
    fun `identity -- inverse returns self`() {
        val bij = Bijection.identity<Any>()
        assertSame(bij, bij.inverse())
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
                Bijection(forward, reverse),
                Bijection(forward, reverse)
            )
            assertEquals(
                Bijection(forward, reverse),
                Bijection(reverse, forward).inverse()
            )

            assertNotEquals(
                Bijection(forward, reverse),
                Bijection(reverse, forward)
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
    fun `Impl -- andThen Bijection`(): Unit =
        runBlocking {
            val bij = intToString.andThen(intToString.inverse())
            Arb.int().forAll { bij(it) == it }
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
    fun `Impl -- infix andThen Bijection`(): Unit =
        runBlocking {
            val bij = intToString andThen intToString.inverse()
            Arb.int().forAll { bij(it) == it }
        }

    @Test
    fun `Impl -- andThen functions`(): Unit =
        runBlocking {
            val bij = intToString.andThen(
                forward = { "_$it" },
                inverse = { it.drop(0) }
            )
            Arb.int().forAll { bij(it) == "_$it" }
        }

    @Test
    fun `Impl -- compose Bijection`(): Unit =
        runBlocking {
            val bij = intToString.compose(Bijection({ it + 1 }, { it - 1 }))
            Arb.int(Int.MIN_VALUE..(Int.MAX_VALUE - 1)).forAll {
                bij(it) == "${it + 1}"
            }
        }

    @Test
    fun `asInjection -- always reversible`(): Unit =
        runBlocking {
            val inj = intToString.asInjection
            Arb.int().forAll {
                inj.invert(inj(it)) == Some(it)
            }
        }

    @Test
    fun `asInjection -- equality`() {
        assertEquals(intToString.asInjection, intToString.asInjection)
        assertNotEquals(plusOne.asInjection, intToString.asInjection)
    }
}
