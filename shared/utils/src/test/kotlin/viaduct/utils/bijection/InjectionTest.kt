@file:Suppress("ForbiddenImport")

package viaduct.utils.bijection

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import viaduct.utils.collections.None
import viaduct.utils.collections.Some

class InjectionTest {
    private val intToString = Injection<Int, String>(
        { it.toString() },
        {
            try {
                Some(it.toInt())
            } catch (_: NumberFormatException) {
                None
            }
        }
    )

    @Test
    fun `identity`(): Unit =
        runBlocking {
            val inj = Injection.identity<Any>()

            // value equality
            Arb.int().forAll {
                val forwardEquals = inj(it) == it
                val invertEquals = inj.invert(it) == Some(it)
                forwardEquals && invertEquals
            }

            // referential equality
            arbitrary { object {} }.forAll {
                val forwardEquals = inj(it) === it
                val invertEquals = inj.invert(it) == Some(it)
                forwardEquals && invertEquals
            }
        }

    @Test
    fun `identity -- referential equality`() {
        assertEquals(Injection.identity<Int>(), Injection.identity<Int>())
    }

    @Test
    fun `one-way impl`(): Unit =
        runBlocking {
            val inj = Injection { it: Int -> it.toString() }

            Arb.int().forAll {
                val forwardEquals = inj(it) == "$it"
                // this injection is defined in only one direction and will never be invertible
                val invertEquals = inj.invert("$it") == None
                forwardEquals && invertEquals
            }
        }

    @Test
    fun `two-way impl`(): Unit =
        runBlocking {
            Arb.int().forAll {
                val forwardEquals = intToString(it) == "$it"
                val invertEquals = intToString.invert("$it") == Some(it)
                forwardEquals && invertEquals
            }
            Arb.string()
                .filter { runCatching { it.toInt() }.exceptionOrNull() is NumberFormatException }
                .forAll { intToString.invert(it) === None }
        }

    @Test
    fun `AndThen -- equality`(): Unit =
        runBlocking {
            val a = Injection { x: Int -> x + 1 }
            val b = Injection { x: Int -> x * 2 }
            assertEquals(
                a.andThen(b),
                a.andThen(b)
            )
            assertNotEquals(
                a.andThen(b),
                a.andThen(a)
            )
        }

    @Test
    fun `AndThen -- invertible injection`(): Unit =
        runBlocking {
            val inj = intToString.andThen(Injection({ "_$it" }, { Some(it.drop(1)) }))

            Arb.int().forAll {
                val forwardEquals = inj(it) == "_$it"
                val invertEquals = inj.invert("_$it") == Some(it)
                forwardEquals && invertEquals
            }
        }

    @Test
    fun `AndThen -- one-way injection`(): Unit =
        runBlocking {
            val inj = intToString.andThen(Injection({ "_$it" }))

            Arb.int().forAll {
                val forwardEquals = inj(it) == "_$it"
                // this injection is defined in only one direction and will never be invertible
                val invertEquals = inj.invert("_$it") == None
                forwardEquals && invertEquals
            }
        }

    @Test
    fun `AndThen -- function`(): Unit =
        runBlocking {
            val inj = intToString.andThen { "_$it" }
            Arb.int().forAll {
                val forwardEquals = inj(it) == "_$it"
                // this injection is defined in only one direction and will never be invertible
                val invertEquals = inj.invert("_$it") == None
                forwardEquals && invertEquals
            }
        }
}
