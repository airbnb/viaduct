@file:Suppress("ForbiddenImport")

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
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.internal.internal
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.arbitrary.graphql.graphQLName
import viaduct.engine.api.mocks.MockSchema

class MocksTest {
    @Test
    fun InternalContext_executionContext() {
        val ec = MockExecutionContext.mk()
        assertSame(ec, ec.internal.executionContext)
    }

    @Test
    fun InternalContext_resolverExecutionContext() {
        val ec = MockResolverExecutionContext.mk()
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
    fun `GlobalIDImpl equals`(): Unit =
        runBlocking {
            Arb.graphQLName().forAll { typeName ->
                val internalId = Arb.string().bind()
                val type = MockType.mkNodeObject(typeName)
                val id1: GlobalID<*> = GlobalIDImpl(type, internalId)
                val id2: GlobalID<*> = GlobalIDImpl(type, internalId)
                id1 == id2
            }
        }

    @Test
    fun MockSelectionsLoader_Factory(): Unit =
        runBlocking {
            class Q : Query
            val q = Q()

            class M : Mutation
            val m = M()

            val factory = MockSelectionsLoader.Factory(q, m)
            Assertions.assertEquals(MockSelectionsLoader(q), factory.forQuery("resolverid"))
            Assertions.assertEquals(MockSelectionsLoader(m), factory.forMutation("resolverid"))
        }

    @Test
    fun MockSelectionsLoader(): Unit =
        runBlocking {
            class Foo : CompositeOutput
            val fooType = MockType("Foo", Foo::class)
            val foo = Foo()

            val loader = MockSelectionsLoader(foo)
            val loaded = loader.load(MockExecutionContext.mk(), SelectionSet.empty(fooType))
            Assertions.assertEquals(foo, loaded)
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
}
