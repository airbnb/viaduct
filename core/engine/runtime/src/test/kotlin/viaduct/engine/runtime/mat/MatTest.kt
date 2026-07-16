package viaduct.engine.runtime.mat

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext

class MatTest {
    @Test
    fun `null mat covers requested tree with successful null source`() =
        runTest {
            val tree = KeyTree.empty
            val handle = object : EngineExecutionContext.ExecutionHandle {}

            val result = Mat.Null(tree, handle)

            assertSame(tree, result.coverage)
            assertTrue(result.source.isSuccess)
            assertNull(result.source.getOrThrow())
        }
}
