package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckerResultTest {
    @Test
    fun testCheckerResultSuccess() {
        val result = CheckerResult.Success
        assertNull(result.asError)
        assertEquals(result, CheckerResult.Success)
    }

    private class TestCheckerResultError(override val error: Exception) : CheckerResult.Error {
        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean {
            return true
        }

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
            return fieldResult
        }
    }

    @Test
    fun testCheckerResultError() {
        val error = IllegalStateException("test")
        val testErrorResult = TestCheckerResultError(error)

        assertEquals(error, testErrorResult.error)
        assertEquals(testErrorResult, testErrorResult.asError)
        assertTrue(testErrorResult.isErrorForResolver(ctx = CheckerResultContext()))
    }

    @Test
    fun testCheckerResultCombine() {
        val successResult = CheckerResult.Success
        val errorResult = TestCheckerResultError(IllegalStateException("test error"))

        assertEquals(errorResult, successResult.combine(errorResult))
        assertEquals(errorResult, errorResult.combine(successResult))

        val errorResult1 = TestCheckerResultError(IllegalStateException("test error1"))
        assertEquals(errorResult1, errorResult.combine(errorResult1))
        assertEquals(errorResult, errorResult1.combine(errorResult))

        val successResult1 = CheckerResult.Success
        assertEquals(successResult, successResult.combine(successResult1))
        assertEquals(successResult1, successResult1.combine(successResult))
    }
}
