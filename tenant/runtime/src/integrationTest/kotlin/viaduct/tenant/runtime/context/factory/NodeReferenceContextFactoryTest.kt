package viaduct.tenant.runtime.context.factory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.internal.NodeReferenceGRTFactoryImpl

@ExperimentalCoroutinesApi
class NodeReferenceContextFactoryTest {
    @Test
    fun default() {
        val nodeRefFactory = NodeReferenceContextFactory.default.mk(MockArgs().engineExecutionContext)
        assertTrue(nodeRefFactory is NodeReferenceGRTFactoryImpl)
    }
}
