@file:Suppress("ForbiddenImport")
@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.api.mocks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.RootObjectFieldImpl
import viaduct.api.internal.internal
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.arbitrary.graphql.graphQLName
import viaduct.engine.api.mocks.MockSchema

class MocksTest {
    @Test
    fun InternalContext_executionContext() {
        val ec = MockExecutionContext.create()
        assertSame(ec, ec.internal.executionContext)
    }

    @Test
    fun InternalContext_resolverExecutionContext() {
        val ec = MockResolverExecutionContext.create()
        assertSame(ec, ec.internal.resolverExecutionContext)
    }

    @Test
    fun `InternalContext_executionContext -- not an ExecutionContext`() {
        val ic = MockInternalContext(MockSchema.minimal)
        val ec = ic.resolverExecutionContext
        assertSame(ec, ec.internal)
        assertThrows<UnsupportedOperationException> {
            ec.selectionsFor(
                Type.ofClass(Query::class),
                ""
            )
        }
    }

    @Test
    fun MockType_mkNodeObject(): Unit =
        runBlocking {
            Arb.graphQLName().forAll { typeName ->
                MockType.mkNodeObject(typeName).name == typeName
            }
        }

    @Test
    fun `GlobalID equals`(): Unit =
        runBlocking {
            Arb.graphQLName().forAll { typeName ->
                val internalId = Arb.string().bind()
                val type = MockType.mkNodeObject(typeName)
                val id1: GlobalID<*> = GlobalID(type, internalId)
                val id2: GlobalID<*> = GlobalID(type, internalId)
                id1 == id2
            }
        }

    @Test
    fun MockReflectionLoader() {
        val foo = MockType("Foo", Object::class)
        val bar = MockType("Bar", Object::class)
        val loader = MockReflectionLoader(foo, bar)

        Assertions.assertEquals(foo, loader.reflectionFor("Foo"))
        Assertions.assertEquals(bar, loader.reflectionFor("Bar"))
        assertThrows<Exception> {
            loader.reflectionFor("Unknown")
        }
    }

    private class FooParent : GRT

    private class FooResult : Object

    private val fooParent = Type.ofClass(FooParent::class)
    private val fooResult = Type.ofClass(FooResult::class)

    private fun fixedResult(value: Object) =
        object : PrebakedRootFieldRefResults {
            @Suppress("UNCHECKED_CAST")
            override fun <A : Arguments, T : Object> get(
                field: viaduct.api.reflect.RootObjectField<*, T, A>,
                arguments: A
            ): T = value as T
        }

    @Test
    fun `rootFieldRef throws when no results are configured`() {
        val ctx = MockResolverExecutionContext.create()
        val field = RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo")
        )
        assertThrows<UnsupportedOperationException> {
            ctx.rootFieldRef(field, Arguments.NoArguments)
        }
    }

    @Test
    fun `rootFieldRef returns the configured result`() {
        val field = RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo")
        )
        val stub = FooResult()
        val ctx = MockResolverExecutionContext<Query>(
            internalContext = MockInternalContext(MockSchema.minimal),
            rootFieldRefResults = fixedResult(stub),
        )

        assertSame(stub, ctx.rootFieldRef(field, Arguments.NoArguments))
    }

    @Test
    fun `rootFieldRef forwards arguments to the results impl`() {
        val field = RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo")
        )
        var capturedArgs: Arguments? = null
        val capturing = object : PrebakedRootFieldRefResults {
            @Suppress("UNCHECKED_CAST")
            override fun <A : Arguments, T : Object> get(
                field: viaduct.api.reflect.RootObjectField<*, T, A>,
                arguments: A
            ): T {
                capturedArgs = arguments
                return FooResult() as T
            }
        }
        val ctx = MockResolverExecutionContext<Query>(
            internalContext = MockInternalContext(MockSchema.minimal),
            rootFieldRefResults = capturing,
        )

        ctx.rootFieldRef(field, Arguments.NoArguments)

        assertSame(Arguments.NoArguments, capturedArgs)
    }

    @Test
    fun `RecordingRootFieldRefResults consumes stubs in declaration order for the same field`() {
        val field = RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo")
        )
        val first = FooResult()
        val second = FooResult()
        val recorder = RecordingRootFieldRefResults.of(field to first, field to second)
        val ctx = MockResolverExecutionContext<Query>(
            internalContext = MockInternalContext(MockSchema.minimal),
            rootFieldRefResults = recorder,
        )

        assertSame(first, ctx.rootFieldRef(field, Arguments.NoArguments))
        assertSame(second, ctx.rootFieldRef(field, Arguments.NoArguments))
        Assertions.assertEquals(2, recorder.calls.size)
    }

    @Test
    fun `RecordingRootFieldRefResults throws when a field is called more times than stubs were configured`() {
        val field = RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo")
        )
        val recorder = RecordingRootFieldRefResults.of(field to FooResult())
        val ctx = MockResolverExecutionContext<Query>(
            internalContext = MockInternalContext(MockSchema.minimal),
            rootFieldRefResults = recorder,
        )

        ctx.rootFieldRef(field, Arguments.NoArguments)
        val ex = assertThrows<IllegalStateException> {
            ctx.rootFieldRef(field, Arguments.NoArguments)
        }
        Assertions.assertTrue(ex.message!!.contains("foo"))
        Assertions.assertTrue(ex.message!!.contains("2 times"))
    }

    @Test
    fun `MockFieldExecutionContext propagates rootFieldRefResults`() {
        val field = RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo")
        )
        val stub = FooResult()
        val ctx = MockFieldExecutionContext(
            objectValue = NullObject,
            queryValue = NullQuery,
            arguments = Arguments.NoArguments,
            requestContext = null,
            selectionsValue = viaduct.api.select.SelectionSet.NoSelections,
            internalContext = MockInternalContext(MockSchema.minimal),
            rootFieldRefResults = fixedResult(stub),
        )

        assertSame(stub, ctx.rootFieldRef(field, Arguments.NoArguments))
    }

    private object NullObject : Object

    private object NullQuery : Query
}
