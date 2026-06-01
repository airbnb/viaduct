@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.api.testing.spec.base

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.RootObjectFieldImpl
import viaduct.api.mocks.PrebakedRootFieldRefResults
import viaduct.api.reflect.Type
import viaduct.api.testing.types.RootFieldRefStub
import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object

/**
 * Verifies the spec-layer plumbing for `rootFieldRefValues`. The user-facing surface
 * is a `List<RootFieldRefStub<*, *>>` keyed exactly on `(pathFromQueryRoot, arguments)`.
 * Calls without a matching stub throw.
 */
class RootFieldRefResultsTest {
    private class TestSpec : BaseResolverSpec() {
        fun resolveResults(): PrebakedRootFieldRefResults = buildRootFieldRefResults()
    }

    private class FooParent : GRT

    private class FooResult : Object

    private class BarResult : Object

    /** Test-local fake [Arguments] with structural equality from `data class`. */
    private data class FakeArgs(val token: String) : Arguments

    private val fooParent = Type.ofClass(FooParent::class)
    private val fooResult = Type.ofClass(FooResult::class)
    private val barResult = Type.ofClass(BarResult::class)

    private fun fooFieldNoArgs() =
        RootObjectFieldImpl<FooParent, FooResult, Arguments.NoArguments>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo"),
        )

    private fun fooFieldWithArgs() =
        RootObjectFieldImpl<FooParent, FooResult, FakeArgs>(
            "foo",
            fooParent,
            fooResult,
            listOf("foo"),
        )

    private fun barField() =
        RootObjectFieldImpl<FooParent, BarResult, Arguments.NoArguments>(
            "bar",
            fooParent,
            barResult,
            listOf("bar"),
        )

    @Test
    fun `default empty list throws on any rootFieldRef call`() {
        val spec = TestSpec()
        assertThrows<IllegalArgumentException> {
            spec.resolveResults().get(fooFieldNoArgs(), Arguments.NoArguments)
        }
    }

    @Test
    fun `argless field stub matches Arguments_NoArguments`() {
        val stub = FooResult()
        val spec = TestSpec().apply {
            rootFieldRefValues = listOf(RootFieldRefStub(fooFieldNoArgs(), Arguments.NoArguments, stub))
        }
        assertSame(stub, spec.resolveResults().get(fooFieldNoArgs(), Arguments.NoArguments))
    }

    @Test
    fun `arguments-keyed stubs return distinct values per arguments`() {
        val resultA = FooResult()
        val resultB = FooResult()
        val spec = TestSpec().apply {
            rootFieldRefValues = listOf(
                RootFieldRefStub(fooFieldWithArgs(), FakeArgs("a"), resultA),
                RootFieldRefStub(fooFieldWithArgs(), FakeArgs("b"), resultB),
            )
        }
        val results = spec.resolveResults()
        assertSame(resultA, results.get(fooFieldWithArgs(), FakeArgs("a")))
        assertSame(resultB, results.get(fooFieldWithArgs(), FakeArgs("b")))
    }

    @Test
    fun `routes by pathFromQueryRoot across distinct fields`() {
        val fooStub = FooResult()
        val barStub = BarResult()
        val spec = TestSpec().apply {
            rootFieldRefValues = listOf(
                RootFieldRefStub(fooFieldNoArgs(), Arguments.NoArguments, fooStub),
                RootFieldRefStub(barField(), Arguments.NoArguments, barStub),
            )
        }
        val results = spec.resolveResults()
        assertSame(fooStub, results.get(fooFieldNoArgs(), Arguments.NoArguments))
        assertSame(barStub, results.get(barField(), Arguments.NoArguments))
    }

    @Test
    fun `mismatched arguments throws and reports the missing key`() {
        val spec = TestSpec().apply {
            rootFieldRefValues = listOf(
                RootFieldRefStub(fooFieldWithArgs(), FakeArgs("a"), FooResult())
            )
        }
        val ex = assertThrows<IllegalArgumentException> {
            spec.resolveResults().get(fooFieldWithArgs(), FakeArgs("b"))
        }
        assertEquals(true, ex.message!!.contains("foo"))
        assertEquals(true, ex.message!!.contains("FakeArgs(token=b)"))
    }

    @Test
    fun `last-stub-wins for duplicate keys`() {
        val first = FooResult()
        val second = FooResult()
        val spec = TestSpec().apply {
            rootFieldRefValues = listOf(
                RootFieldRefStub(fooFieldWithArgs(), FakeArgs("a"), first),
                RootFieldRefStub(fooFieldWithArgs(), FakeArgs("a"), second),
            )
        }
        assertSame(second, spec.resolveResults().get(fooFieldWithArgs(), FakeArgs("a")))
    }

    @Test
    fun `is stateless across repeated calls to the same key`() {
        val stub = FooResult()
        val spec = TestSpec().apply {
            rootFieldRefValues = listOf(RootFieldRefStub(fooFieldWithArgs(), FakeArgs("a"), stub))
        }
        val results = spec.resolveResults()
        repeat(3) {
            assertSame(stub, results.get(fooFieldWithArgs(), FakeArgs("a")))
        }
    }
}
