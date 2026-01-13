package viaduct.tenant.runtime.context.factory

import io.mockk.mockk
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.NodeResolverBase
import viaduct.api.mocks.MockType
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query as QueryType
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.User

/**
 * Tests code in ResolverExectionContextFactoryBase
 */
class ResolverExecutionContextFactoryBaseTest {
    private val contextMocks = ContextMocks(GlobalIdFeatureAppTest.schema)
    private val globalIDCodec = GlobalIDCodecDefault
    private val reflectionLoader = mockReflectionLoader("viaduct.tenant.runtime.globalid")

    @Suppress("UNCHECKED_CAST")
    private val resolverBase =
        NodeExecutionContextFactory.FakeResolverBase::class.java as Class<out NodeResolverBase<User>>

    @Test
    fun `NodeExecutionContextFactory with Composite type and null selections throws IllegalArgumentException`() {
        val type = MockType("User", User::class)
        val nodeFactory = NodeExecutionContextFactory(resolverBase, globalIDCodec, reflectionLoader, type)

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

        val nodeFactory = NodeExecutionContextFactory(resolverBase, globalIDCodec, reflectionLoader, notCompositeType)

        // Create a mock RawSelectionSet (non-null) to trigger the validation
        val mockRawSelectionSet = mockk<RawSelectionSet>()

        val exception = assertThrows<IllegalArgumentException> {
            // Call the factory to trigger toSelectionSet validation
            nodeFactory(
                ContextMocks(GlobalIdFeatureAppTest.schema).engineExecutionContext,
                mockRawSelectionSet, // This non-null selection set should cause validation failure
                null, // requestContext
                "test-id"
            )
        }

        assertTrue(
            exception.message?.contains(" non-null ") ?: false,
            "Error message should mention 'non-null': ${exception.message}"
        )
    }

    @Test
    fun `NodeExecutionContextFactory with no Context in ResolverBase throws IllegalArgumentException`() {
        val badResolverBase = BadResolverBase::class.java
        val type = MockType("User", User::class)

        val exception = assertThrows<IllegalArgumentException> {
            NodeExecutionContextFactory(badResolverBase, globalIDCodec, reflectionLoader, type)
        }

        assertTrue(
            exception.message?.contains(" Context ") ?: false,
            "Error message should mention 'Context': ${exception.message}"
        )
    }

    class BadResolverBase : NodeResolverBase<User> {
        class Context(ctx: FieldExecutionContext<Object, QueryType, Arguments.NoArguments, User>) :
            FieldExecutionContext<Object, QueryType, Arguments.NoArguments, User> by ctx
    }
}
