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
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.RootFieldCall
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.RootObjectFieldImpl
import viaduct.api.internal.internal
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
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

    @Test
    fun `rootFieldRef throws when no referenceSpy is provided`() {
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
    fun `ref resolves the typed root field call with this context`() {
        val ctx = MockResolverExecutionContext.create()
        val expected = FooResult()
        var receivedContext: ResolverExecutionContext<*>? = null
        val call = object : RootFieldCall<FooResult> {
            override fun resolve(context: ResolverExecutionContext<*>): FooResult {
                receivedContext = context
                return expected
            }
        }

        assertSame(expected, ctx.ref(call))
        assertSame(ctx, receivedContext)
    }

    @Test
    fun `MockFieldExecutionContext exposes independently supplied owned selections`() {
        val requested = SelectionSet.empty(fooResult)
        val owned = SelectionSet.empty(fooResult)
        val ctx = MockFieldExecutionContext(
            objectValue = NullObject,
            queryValue = NullQuery,
            arguments = Arguments.NoArguments,
            requestContext = null,
            selectionsValue = requested,
            internalContext = MockInternalContext(MockSchema.minimal),
            ownedSelectionsValue = owned,
        )

        assertSame(requested, ctx.selections())
        assertSame(owned, ctx.ownedSelections())
    }

    private object NullObject : Object

    private object NullQuery : Query
}
