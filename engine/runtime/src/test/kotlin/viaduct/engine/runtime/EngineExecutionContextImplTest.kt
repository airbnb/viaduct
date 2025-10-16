package viaduct.engine.runtime

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks

class EngineExecutionContextImplTest {
    @Test
    fun `copy keeps dataloaders request-scoped`() {
        val eec = ContextMocks().engineExecutionContextImpl
        val eecCopy = eec.copy(mockk())
        val resolver = mockk<NodeResolverExecutor> { every { typeName } returns "User" }
        val batchNodeLoader = eec.nodeDataLoader(resolver)
        val copyBatchNodeLoader = eecCopy.nodeDataLoader(resolver)
        assertSame(batchNodeLoader, copyBatchNodeLoader)
        assertSame(eec.engine, eecCopy.engine)
        assertTrue(eecCopy.executeAccessChecksInModstrat)
    }
}
