@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.api.testing.spec.base

import graphql.schema.GraphQLInputObjectType
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.ExecutionContext
import viaduct.api.context.RootFieldCall
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.RootObjectFieldImpl
import viaduct.api.mocks.MockFieldExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockResolverExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.testing.types.NullObject
import viaduct.api.testing.types.NullQuery
import viaduct.api.testing.types.ReferenceSpy
import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.mocks.MockSchema

class ReferenceSpyTestResult(
    context: InternalContext,
    engineObjectData: EngineObjectData,
) : ObjectBase(context, engineObjectData)

class ReferenceSpyTest {
    private class FooParent : GRT

    private class FakeArgs(
        token: String,
        internalContext: InternalContext,
    ) : InputLikeBase(), Arguments {
        override val inputData = mapOf("token" to token)
        override val graphQLInputObjectType: GraphQLInputObjectType =
            GraphQLInputObjectType.newInputObject()
                .name("FakeArgs")
                .field {
                    it.name("token").type(graphql.Scalars.GraphQLString)
                }
                .build()
        override val context = internalContext
    }

    private class OtherArgs(
        label: String,
        internalContext: InternalContext,
    ) : InputLikeBase(), Arguments {
        override val inputData = mapOf("label" to label)
        override val graphQLInputObjectType: GraphQLInputObjectType =
            GraphQLInputObjectType.newInputObject()
                .name("OtherArgs")
                .field {
                    it.name("label").type(graphql.Scalars.GraphQLString)
                }
                .build()
        override val context = internalContext
    }

    private val internalContext = MockInternalContext(
        MockSchema.mk(
            """
            type ReferenceSpyTestResult {
              value: String
            }
            extend type Query {
              foo: ReferenceSpyTestResult
              bar: ReferenceSpyTestResult
            }
            """.trimIndent()
        )
    )

    private val fooParent = Type.ofClass(FooParent::class)
    private val resultType = Type.ofClass(ReferenceSpyTestResult::class)

    private fun fooFieldNoArgs() =
        RootObjectFieldImpl<FooParent, ReferenceSpyTestResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            resultType,
            listOf("foo"),
        )

    private fun fooFieldWithArgs() =
        RootObjectFieldImpl<FooParent, ReferenceSpyTestResult, FakeArgs>(
            "foo",
            fooParent,
            resultType,
            listOf("foo"),
        )

    private fun barField() =
        RootObjectFieldImpl<FooParent, ReferenceSpyTestResult, Arguments.NoArguments>(
            "bar",
            fooParent,
            resultType,
            listOf("bar"),
        )

    private fun barFieldWithArgs() =
        RootObjectFieldImpl<FooParent, ReferenceSpyTestResult, OtherArgs>(
            "bar",
            fooParent,
            resultType,
            listOf("bar"),
        )

    private fun <A : Arguments, T : Object> referenceCall(
        field: RootObjectFieldImpl<FooParent, T, A>,
        arguments: A,
    ): RootFieldCall<T> =
        object : RootFieldCall<T> {
            override fun field() = field

            override fun arguments(context: ExecutionContext) = arguments
        }

    private fun context(spy: ReferenceSpy): MockResolverExecutionContext<Query> {
        return MockResolverExecutionContext(
            internalContext = internalContext,
            referenceSpy = spy,
        )
    }

    @Test
    fun `assertCalledExactly matches calls in order`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldNoArgs(), Arguments.NoArguments))
        context.ref(referenceCall(barField(), Arguments.NoArguments))

        spy.assertCalledExactly(
            referenceCall(fooFieldNoArgs(), Arguments.NoArguments),
            referenceCall(barField(), Arguments.NoArguments),
        )
    }

    @Test
    fun `assertCalledExactly compares arguments`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("actual", internalContext)))

        val error = assertThrows<AssertionError> {
            spy.assertCalledExactly(
                referenceCall(fooFieldWithArgs(), FakeArgs("expected", internalContext))
            )
        }

        assertTrue(error.message!!.contains("token=expected"))
        assertTrue(error.message!!.contains("token=actual"))
    }

    @Test
    fun `assertCalledExactly reports missing and unexpected calls`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldNoArgs(), Arguments.NoArguments))

        val error = assertThrows<AssertionError> {
            spy.assertCalledExactly(referenceCall(barField(), Arguments.NoArguments))
        }

        assertTrue(error.message!!.contains("Expected:"))
        assertTrue(error.message!!.contains("Actual:"))
        assertTrue(error.message!!.contains("bar"))
        assertTrue(error.message!!.contains("foo"))
    }

    @Test
    fun `assertCalledExactly preserves repeated calls`() {
        val spy = ReferenceSpy()
        val context = context(spy)
        val call = referenceCall(fooFieldNoArgs(), Arguments.NoArguments)

        repeat(2) { context.ref(call) }

        spy.assertCalledExactly(call, call)
    }

    @Test
    fun `assertCallArgumentsOf sees every call to the field in order`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("first", internalContext)))
        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("second", internalContext)))

        spy.assertCallArgumentsOf(fooFieldWithArgs()) { args ->
            args.map { it.inputData["token"] } == listOf("first", "second")
        }
    }

    @Test
    fun `assertCallArgumentsOf ignores calls to other fields`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(barField(), Arguments.NoArguments))
        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("only", internalContext)))

        spy.assertCallArgumentsOf(fooFieldWithArgs()) { args -> args.size == 1 }
    }

    @Test
    fun `assertCallArgumentsOf keeps each field's arguments separate when several types are recorded`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("first foo", internalContext)))
        context.ref(referenceCall(barFieldWithArgs(), OtherArgs("only bar", internalContext)))
        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("second foo", internalContext)))

        spy.assertCallArgumentsOf(fooFieldWithArgs()) { args ->
            args.map { it.inputData["token"] } == listOf("first foo", "second foo")
        }
        spy.assertCallArgumentsOf(barFieldWithArgs()) { args ->
            args.map { it.inputData["label"] } == listOf("only bar")
        }
        spy.assertCallArgumentsOfFirst(barFieldWithArgs()) { it.inputData["label"] == "only bar" }
    }

    @Test
    fun `assertCallArgumentsOf failure names the field and renders the recorded calls`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("actual", internalContext)))

        val error = assertThrows<AssertionError> {
            spy.assertCallArgumentsOf(fooFieldWithArgs()) { false }
        }

        assertTrue(error.message!!.contains("foo"))
        assertTrue(error.message!!.contains("token=actual"))
    }

    @Test
    fun `assertCallArgumentsOfFirst reads the first call to the field`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("first", internalContext)))
        context.ref(referenceCall(fooFieldWithArgs(), FakeArgs("second", internalContext)))

        spy.assertCallArgumentsOfFirst(fooFieldWithArgs()) { it.inputData["token"] == "first" }
    }

    @Test
    fun `assertCallArgumentsOfFirst fails when the field was never referenced`() {
        val spy = ReferenceSpy()
        val context = context(spy)

        context.ref(referenceCall(barField(), Arguments.NoArguments))

        val error = assertThrows<AssertionError> {
            spy.assertCallArgumentsOfFirst(fooFieldWithArgs()) { true }
        }

        assertTrue(error.message!!.contains("none was created"))
    }

    @Test
    fun `resolver receives an opaque root field reference`() {
        val spy = ReferenceSpy()

        val result = context(spy).ref(referenceCall(fooFieldNoArgs(), Arguments.NoArguments))

        assertTrue(result.__engineObject is RootFieldReference)
    }

    @Test
    fun `ref builds the call arguments with this context`() {
        val spy = ReferenceSpy()
        val context = context(spy)
        var receivedContext: ExecutionContext? = null
        val call = object : RootFieldCall<ReferenceSpyTestResult> {
            override fun field() = fooFieldNoArgs()

            override fun arguments(context: ExecutionContext): Arguments {
                receivedContext = context
                return Arguments.NoArguments
            }
        }

        context.ref(call)

        assertSame(context, receivedContext)
    }

    @Test
    fun `MockFieldExecutionContext records through the spy it was given`() {
        val spy = ReferenceSpy()
        val ctx = MockFieldExecutionContext(
            objectValue = NullObject,
            queryValue = NullQuery,
            arguments = Arguments.NoArguments,
            requestContext = null,
            selectionsValue = SelectionSet.NoSelections,
            internalContext = internalContext,
            referenceSpy = spy,
        )

        ctx.ref(referenceCall(fooFieldNoArgs(), Arguments.NoArguments))

        spy.assertCalledExactly(referenceCall(fooFieldNoArgs(), Arguments.NoArguments))
    }
}
