package viaduct.engine.runtime.mat

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData

class MatResultTest {
    @Test
    fun `successful null source is preserved`() {
        val coverage = KeyTree.empty

        val result = MatResult(coverage, Result.success(null))

        assertSame(coverage, result.coverage)
        assertTrue(result.source.isSuccess)
        assertNull(result.source.getOrThrow())
    }

    @Test
    fun `failed source is preserved`() {
        val failure = IllegalStateException("materialization failed")

        val result = MatResult(
            KeyTree.empty,
            Result.failure<EngineObjectData?>(failure),
        )

        assertTrue(result.source.isFailure)
        assertSame(failure, result.source.exceptionOrNull())
    }
}
