package viaduct.tenant.runtime.context.factory

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldResolverBase
import viaduct.api.MutationResolverBase
import viaduct.api.ResolverBase
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.testGlobalId
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.tenant.runtime.FakeMutation
import viaduct.tenant.runtime.FakeObject
import viaduct.tenant.runtime.FakeQuery

/**
 * Tests for ResolverExecutionContextFactory - tests that the factory correctly constructs
 * framework contexts and selects the correct field context kind.
 *
 * WHAT THESE TESTS ARE TESTING:
 * - NodeExecutionContextFactory context creation
 * - FieldExecutionContextFactory.of() field-base validation
 * - FieldExecutionContextFactory context creation
 *
 * WHAT THESE TESTS ARE NOT TESTING:
 * - Actual resolver execution (tested in behavioral tests)
 * - Selection set processing (tested in other tests)
 * - Variable resolution (tested in other tests)
 */
class ResolverExecutionContextFactoryTest {
    // Create mock reflection types for Query
    private val queryReflection = object : Type<Query> {
        override val name = "Query"
        override val kcls = FakeQuery::class
    }

    private val mutationReflection = object : Type<Mutation> {
        override val name = "Mutation"
        override val kcls = FakeMutation::class
    }

    private val testNodeReflection = object : Type<TestNode> {
        override val name = "TestNode"
        override val kcls = TestNode::class
    }

    private val reflectionLoader = MockReflectionLoader(queryReflection, mutationReflection, testNodeReflection)

    private val schema = MockSchema.mk(
        """
        extend type Query {
            testField: String
        }

        extend type Mutation {
            testMutation: String
        }

        type TestObject implements Node {
            id: ID!
            value: String
        }
        """.trimIndent()
    )

    // ============================================================================
    // NodeExecutionContextFactory Tests
    // ============================================================================

    @Test
    fun `NodeExecutionContextFactory -- successful construction with valid resolver base`() {
        // Should successfully construct factory when resolver has valid nested Context class
        val factory = NodeExecutionContextFactory(
            reflectionLoader,
            Type.ofClass(TestNode::class),
            DefaultGRTConvFactory
        )
        assertNotNull(factory)

        val contextMocks = ContextMocks(myFullSchema = schema)
        // GlobalIDCodecDefault uses Base64-encoded format
        val testNodeId = testNodeReflection.testGlobalId("test-id-123")

        assertNotNull(
            factory(
                engineExecutionContext = contextMocks.engineExecutionContext,
                selections = mockk(relaxed = true),
                requestContext = null,
                id = testNodeId,
            )
        )
    }

    // ============================================================================
    // FieldExecutionContextFactory.of() Tests
    // ============================================================================

    @Test
    fun `FieldExecutionContextFactory_of -- successful construction for field resolver`() {
        // Should successfully construct factory for a field with resolver
        // Disabled because it requires Query types with GRT primary constructors
        // The validation aspects are tested by the error case tests below
        val factory = FieldExecutionContextFactory.of(
            FakeFieldResolverBase::class.java,
            reflectionLoader,
            schema,
            "Query",
            "testField",
            DefaultGRTConvFactory
        )

        val contextMocks = ContextMocks(myFullSchema = schema)

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            engineSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
        )
        result.shouldBeInstanceOf<FieldExecutionContext<*, *, *, *>>()
    }

    @Test
    fun `FieldExecutionContextFactory_of -- successful construction for mutation resolver`() {
        // Should successfully construct factory for mutation field
        // Disabled because it requires Query/Mutation types with GRT primary constructors
        // The validation aspects are tested by the error case tests below
        val factory = FieldExecutionContextFactory.of(
            FakeMutationResolverBase::class.java,
            reflectionLoader,
            schema,
            "Mutation",
            "testMutation",
            DefaultGRTConvFactory
        )

        val contextMocks = ContextMocks(myFullSchema = schema)

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            engineSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
        )
        result.shouldBeInstanceOf<MutationFieldExecutionContext<*, *, *, *>>()
    }

    @Test
    fun `FieldExecutionContextFactory_of -- fails for missing field coordinate`() {
        // Should fail when field doesn't exist in schema
        val exception = assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory.of(
                FakeFieldResolverBase::class.java,
                reflectionLoader,
                schema,
                "Query",
                "nonExistentField",
                DefaultGRTConvFactory
            )
        }

        assertNotNull(exception.message)
        assertTrue(exception.message!!.startsWith("Called on a missing field coordinate"))
    }

    @Test
    fun `FieldExecutionContextFactory_of -- fails for unsupported resolver base`() {
        assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory.of(
                UnsupportedFieldResolverBase::class.java,
                reflectionLoader,
                schema,
                "Query",
                "testField",
                DefaultGRTConvFactory
            )
        }
    }

    // ============================================================================
    // FieldExecutionContextFactory Constructor Tests (direct instantiation)
    // ============================================================================

    @Test
    fun `FieldExecutionContextFactory constructor -- constructs FieldExecutionContext`() {
        // Should construct the requested framework context directly.
        @Suppress("UNCHECKED_CAST")
        val factory = FieldExecutionContextFactory(
            FieldExecutionContext::class.java,
            reflectionLoader,
            Type.ofClass(CompositeOutput.NotComposite::class),
            Arguments.NoArguments::class as KClass<Arguments>,
            FakeObject::class as KClass<Object>,
            FakeQuery::class as KClass<Query>,
            DefaultGRTConvFactory
        )
        val contextMocks = ContextMocks(myFullSchema = schema)

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            engineSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
        )
        result.shouldBeInstanceOf<FieldExecutionContext<*, *, *, *>>()
    }

    @Test
    fun `FieldExecutionContextFactory constructor -- constructs MutationFieldExecutionContext`() {
        // Verify mutation fields receive the mutation-specific framework context.
        @Suppress("UNCHECKED_CAST")
        val factory = FieldExecutionContextFactory(
            MutationFieldExecutionContext::class.java,
            reflectionLoader,
            Type.ofClass(CompositeOutput.NotComposite::class),
            Arguments.NoArguments::class as KClass<Arguments>,
            FakeObject::class as KClass<Object>,
            FakeQuery::class as KClass<Query>,
            DefaultGRTConvFactory
        )

        val contextMocks = ContextMocks(myFullSchema = schema)

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            engineSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
        )
        result.shouldBeInstanceOf<MutationFieldExecutionContext<*, *, *, *>>()
    }

    // ============================================================================
    // Test Fixtures
    // ============================================================================

    class TestNode(val internalId: String) : NodeObject

    // GRT test fixtures with proper primary constructors
    private abstract class FakeFieldResolverBase :
        FieldResolverBase<FakeObject, FakeQuery, Arguments.NoArguments, String?>

    private abstract class FakeMutationResolverBase :
        MutationResolverBase<FakeQuery, FakeMutation, Arguments.NoArguments, String?>

    private abstract class UnsupportedFieldResolverBase : ResolverBase<Object>
}
