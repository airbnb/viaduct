package viaduct.tenant.runtime.context.factory

import io.mockk.mockk
import kotlin.reflect.KClass
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import strikt.api.expectThat
import strikt.assertions.isNotNull
import strikt.assertions.startsWith
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ResolverBase
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
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.FakeMutation
import viaduct.tenant.runtime.FakeObject
import viaduct.tenant.runtime.FakeQuery

/**
 * Tests for ResolverExecutionContextFactory - tests that the factory correctly constructs
 * context wrappers and validates nested Context classes.
 *
 * WHAT THESE TESTS ARE TESTING:
 * - NodeExecutionContextFactory constructor validation and wrapper creation
 * - FieldExecutionContextFactory.of() static method validation
 * - FieldExecutionContextFactory constructor validation
 * - Context wrapper class detection and wrapping
 * - Type safety of KFunction.call usage in wrap() method
 *
 * WHAT THESE TESTS ARE NOT TESTING:
 * - Actual resolver execution (tested in behavioral tests)
 * - Selection set processing (tested in other tests)
 * - Variable resolution (tested in other tests)
 */
class ResolverExecutionContextFactoryTest {
    private val codec = GlobalIDCodecDefault

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
            NodeExecutionContextFactory.FakeResolverBase::class.java,
            codec,
            reflectionLoader,
            Type.ofClass(TestNode::class)
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

    @Test
    fun `NodeExecutionContextFactory -- fails when no nested Context class exists`() {
        // Should fail when resolver doesn't have a nested Context class
        assertThrows<IllegalArgumentException> {
            NodeExecutionContextFactory(
                InvalidNodeResolverWithoutContext::class.java,
                codec,
                reflectionLoader,
                Type.ofClass(TestNode::class)
            )
        }
    }

    @Test
    fun `NodeExecutionContextFactory -- fails when Context class is not correct type`() {
        // Should fail when nested Context doesn't extend NodeExecutionContext
        assertThrows<IllegalArgumentException> {
            NodeExecutionContextFactory(
                InvalidNodeResolverWrongContextType::class.java,
                codec,
                reflectionLoader,
                Type.ofClass(TestNode::class)
            )
        }
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
            FieldExecutionContextFactory.FakeResolverBase::class.java,
            codec,
            reflectionLoader,
            schema,
            "Query",
            "testField"
        )

        val contextMocks = ContextMocks(myFullSchema = schema)
        val queryType = schema.schema.queryType

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            rawSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
            rawObjectValue = mkEngineObjectData(queryType, emptyMap()),
            rawQueryValue = mkEngineObjectData(queryType, emptyMap()),
        )
        assertInstanceOf(FieldExecutionContext::class.java, result)
    }

    @Test
    fun `FieldExecutionContextFactory_of -- successful construction for mutation resolver`() {
        // Should successfully construct factory for mutation field
        // Disabled because it requires Query/Mutation types with GRT primary constructors
        // The validation aspects are tested by the error case tests below
        val factory = FieldExecutionContextFactory.of(
            FakeMutationResolverBase::class.java,
            codec,
            reflectionLoader,
            schema,
            "Mutation",
            "testMutation"
        )

        val contextMocks = ContextMocks(myFullSchema = schema)
        val queryType = schema.schema.queryType

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            rawSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
            rawObjectValue = mkEngineObjectData(queryType, emptyMap()),
            rawQueryValue = mkEngineObjectData(queryType, emptyMap()),
        )
        assertInstanceOf(MutationFieldExecutionContext::class.java, result)
    }

    @Test
    fun `FieldExecutionContextFactory_of -- fails for missing field coordinate`() {
        // Should fail when field doesn't exist in schema
        val exception = assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory.of(
                FieldExecutionContextFactory.FakeResolverBase::class.java,
                codec,
                reflectionLoader,
                schema,
                "Query",
                "nonExistentField"
            )
        }

        expectThat(exception.message).isNotNull().startsWith("Called on a missing field coordinate")
    }

    @Test
    fun `FieldExecutionContextFactory_of -- fails when no nested Context class exists`() {
        // Should fail when resolver doesn't have nested Context class
        assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory.of(
                InvalidFieldResolverWithoutContext::class.java,
                codec,
                reflectionLoader,
                schema,
                "Query",
                "testField"
            )
        }
    }

    @Test
    fun `FieldExecutionContextFactory_of -- fails when Context is wrong type`() {
        // Should fail when nested Context doesn't extend FieldExecutionContext
        assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory.of(
                InvalidFieldResolverWrongContextType::class.java,
                codec,
                reflectionLoader,
                schema,
                "Query",
                "testField"
            )
        }
    }

    // ============================================================================
    // FieldExecutionContextFactory Constructor Tests (direct instantiation)
    // ============================================================================

    @Test
    fun `FieldExecutionContextFactory constructor -- constructs FieldExecutionContext`() {
        // Should successfully construct factory when resolver has valid nested Context class
        @Suppress("UNCHECKED_CAST")
        val factory = FieldExecutionContextFactory(
            FieldExecutionContextFactory.FakeResolverBase::class.java,
            FieldExecutionContext::class.java,
            codec,
            reflectionLoader,
            Type.ofClass(CompositeOutput.NotComposite::class),
            Arguments.NoArguments::class as KClass<Arguments>,
            FakeObject::class as KClass<Object>,
            FakeQuery::class as KClass<Query>
        )
        val contextMocks = ContextMocks(myFullSchema = schema)
        val queryType = schema.schema.queryType

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            rawSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
            rawObjectValue = mkEngineObjectData(queryType, emptyMap()),
            rawQueryValue = mkEngineObjectData(queryType, emptyMap()),
        )
        assertInstanceOf(FieldExecutionContext::class.java, result)
    }

    @Test
    fun `FieldExecutionContextFactory constructor -- constructs MutationFieldExecutionContext`() {
        // Verify the ctor is set to MutationFieldExecutionContextImpl's primary constructor
        @Suppress("UNCHECKED_CAST")
        val factory = FieldExecutionContextFactory(
            FakeMutationResolverBase::class.java,
            MutationFieldExecutionContext::class.java,
            codec,
            reflectionLoader,
            Type.ofClass(CompositeOutput.NotComposite::class),
            Arguments.NoArguments::class as KClass<Arguments>,
            FakeObject::class as KClass<Object>,
            FakeQuery::class as KClass<Query>
        )

        val contextMocks = ContextMocks(myFullSchema = schema)
        val queryType = schema.schema.queryType

        val result = factory(
            engineExecutionContext = contextMocks.engineExecutionContext,
            rawSelections = null,
            requestContext = null,
            rawArguments = emptyMap(),
            rawObjectValue = mkEngineObjectData(queryType, emptyMap()),
            rawQueryValue = mkEngineObjectData(queryType, emptyMap()),
        )
        assertInstanceOf(MutationFieldExecutionContext::class.java, result)
    }

    @Test
    fun `FieldExecutionContextFactory constructor -- fails when no nested Context class exists`() {
        // Should fail when resolver doesn't have nested Context class
        @Suppress("UNCHECKED_CAST")
        assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory(
                InvalidFieldResolverWithoutContext::class.java,
                FieldExecutionContext::class.java,
                codec,
                reflectionLoader,
                Type.ofClass(CompositeOutput.NotComposite::class),
                Arguments.NoArguments::class as KClass<Arguments>,
                FakeObject::class as KClass<Object>,
                FakeQuery::class as KClass<Query>
            )
        }
    }

    @Test
    fun `FieldExecutionContextFactory constructor -- fails when Context is wrong type`() {
        // Should fail when nested Context doesn't extend FieldExecutionContext
        @Suppress("UNCHECKED_CAST")
        assertThrows<IllegalArgumentException> {
            FieldExecutionContextFactory(
                InvalidFieldResolverWrongContextType::class.java,
                FieldExecutionContext::class.java,
                codec,
                reflectionLoader,
                Type.ofClass(CompositeOutput.NotComposite::class),
                Arguments.NoArguments::class as KClass<Arguments>,
                FakeObject::class as KClass<Object>,
                FakeQuery::class as KClass<Query>
            )
        }
    }

    // Note: invoke() tests for FieldExecutionContextFactory are covered by integration tests
    // like FieldExecutionContextFactoryCtorBugTest which use MockTenantModuleBootstrapper

    // ============================================================================
    // Test Fixtures
    // ============================================================================

    class TestNode(val internalId: String) : NodeObject

    // GRT test fixtures with proper primary constructors
    private class InvalidNodeResolverWithoutContext : NodeResolverBase<TestNode> {
        // Missing nested Context class
    }

    private class InvalidNodeResolverWrongContextType : NodeResolverBase<TestNode> {
        // Has a nested Context class but wrong type
        class Context {
            // Not extending NodeExecutionContext
        }
    }

    private class InvalidFieldResolverWithoutContext : ResolverBase<Object> {
        // Missing nested Context class
    }

    private class InvalidFieldResolverWrongContextType : ResolverBase<Object> {
        // Has a nested Context class but wrong type
        class Context {
            // Not extending FieldExecutionContext
        }
    }

    private class FakeMutationResolverBase : ResolverBase<CompositeOutput> {
        @Suppress("UNCHECKED_CAST")
        class Context(ctx: MutationFieldExecutionContext<*, *, *>) :
            MutationFieldExecutionContext<Query, Arguments, CompositeOutput> by (ctx as MutationFieldExecutionContext<Query, Arguments, CompositeOutput>),
            InternalContext by (ctx as InternalContext)
    }
}
