@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.mapping.graphql.ValueMapper

class ValueGenTest {
    @Test
    fun `mk`() {
        val gen = ValueGen.mk { t: String -> t }
        assertEquals("A", gen("A"))
    }

    @Test
    fun `map`() {
        val gen = ValueGen.mk { t: Int -> t.toString() }
            .map(
                ValueMapper.mk { type: Int, from: String ->
                    type.toString() + from
                }
            )
        assertEquals("11", gen(1))
    }

    @Test
    fun `memoize`(): Unit =
        runBlocking {
            Arb.int().forAll { i ->
                val gen = ValueGen.mk { _: Int -> Random.nextInt() }.memoized()
                gen(i) == gen(i)
            }
        }
}
