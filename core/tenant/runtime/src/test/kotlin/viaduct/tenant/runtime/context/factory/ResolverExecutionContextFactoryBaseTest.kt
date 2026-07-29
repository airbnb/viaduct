package viaduct.tenant.runtime.context.factory

import io.mockk.mockk
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.mocks.MockType
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.tenant.runtime.globalid.GlobalIdTestSchema
import viaduct.tenant.runtime.globalid.User

/**
 * Tests code in ResolverExectionContextFactoryBase
 */
class ResolverExecutionContextFactoryBaseTest {
    private val contextMocks = ContextMocks(GlobalIdTestSchema.schema)
    private val reflectionLoader = mockReflectionLoader("viaduct.tenant.runtime.globalid")

    @Test
    fun `NodeExecutionContextFactory with Composite type and null selections throws IllegalArgumentException`() {
        val type = MockType("User", User::class)
        val nodeFactory = NodeExecutionContextFactory(reflectionLoader, type, DefaultGRTConvFactory)

        val exception = assertThrows<IllegalArgumentException> {
            // Call the factory to trigger toSelectionSet validation
            nodeFactory(
                contextMocks.engineExecutionContext,
                null, // This null selection set should cause validation failure
                null, // requestContext
                "test-id"
            )
        }

        assertTrue(
            exception.message?.contains(" null ") ?: false,
            "Error message should mention 'null': ${exception.message}"
        )
    }

    @Test
    fun `NodeExecutionContextFactory with NotComposite type and selections throws IllegalArgumentException`() {
        @Suppress("UNCHECKED_CAST")
        val notCompositeType = MockType("FakeNotComposite", CompositeOutput.NotComposite::class as KClass<out User>)

        val nodeFactory = NodeExecutionContextFactory(reflectionLoader, notCompositeType, DefaultGRTConvFactory)

        // Create a mock EngineSelectionSet (non-null) to trigger the validation
        val mockEngineSelectionSet = mockk<EngineSelectionSet>()

        val exception = assertThrows<IllegalArgumentException> {
            // Call the factory to trigger toSelectionSet validation
            nodeFactory(
                ContextMocks(GlobalIdTestSchema.schema).engineExecutionContext,
                mockEngineSelectionSet, // This non-null selection set should cause validation failure
                null, // requestContext
                "test-id"
            )
        }

        assertTrue(
            exception.message?.contains(" non-null ") ?: false,
            "Error message should mention 'non-null': ${exception.message}"
        )
    }
}
