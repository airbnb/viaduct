package viaduct.engine.runtime2.execution

import java.util.concurrent.ExecutorService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixture
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.ResolutionDispatcherFactory

class ExecutionTestFixtureOwnershipTest {
    @Test
    fun `default fixture closes its owned dispatcher`() {
        val fixture = ExecutionTestFixture.fromSDL("type Query { value: Int }")
        assertFalse(requireNotNull(fixture.ownedResolverDispatcherIsShutdown()))

        fixture.close()

        assertTrue(requireNotNull(fixture.ownedResolverDispatcherIsShutdown()))
    }

    @Test
    fun `fixture does not close a borrowed dispatcher`() {
        ResolutionDispatcherFactory.create(1).use { dispatcher ->
            val fixture =
                ExecutionTestFixture.fromWorld(
                    schemaSDL = "type Query { value: Int }",
                    world = TestWorld.fromSDL("type Query { value: Int }"),
                    resolverCoroutineContext = dispatcher,
                )
            val executor = dispatcher.executor as ExecutorService

            fixture.close()

            assertNull(fixture.ownedResolverDispatcherIsShutdown())
            assertFalse(executor.isShutdown)
        }
    }
}
