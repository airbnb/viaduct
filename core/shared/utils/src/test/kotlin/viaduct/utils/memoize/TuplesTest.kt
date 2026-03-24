package viaduct.utils.memoize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TuplesTest {
    @Test
    fun testQuadruple() {
        assertEquals(Quadruple(1, 2, 3, 4).toString(), "(1, 2, 3, 4)")
    }

    @Test
    fun testQuintuple() {
        assertEquals(Quintuple(1, 2, 3, 4, 5).toString(), "(1, 2, 3, 4, 5)")
    }

    @Test
    fun testSextuple() {
        assertEquals(Sextuple(1, 2, 3, 4, 5, 6).toString(), "(1, 2, 3, 4, 5, 6)")
    }

    @Test
    fun testSeptuple() {
        assertEquals(Septuple(1, 2, 3, 4, 5, 6, 7).toString(), "(1, 2, 3, 4, 5, 6, 7)")
    }
}
