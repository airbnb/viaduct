package viaduct.api.context

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject

/**
 * A minimal test-only NodeObject that mimics generated classes:
 * it defines a nested `object Reflection : Type<...>` with `name` and `kcls`.
 */
class DummyNode : NodeObject {
    object Reflection : Type<DummyNode> {
        override val name: String = "DummyNode"
        override val kcls: KClass<DummyNode> = DummyNode::class
    }
}

/** A NodeObject without a nested Reflection, to exercise the error path. */
class NoReflectionNode : NodeObject

class ResolverExecutionContextExtensionsTest {
    @Test
    fun `nodeFor T forwards to ctx with Type from nested Reflection`() {
        val ctx = mockk<ResolverExecutionContext>()
        val gid = mockk<GlobalID<DummyNode>>()
        val returned = DummyNode()

        val typeSlot = slot<Type<DummyNode>>()

        every { ctx.globalIDFor(capture(typeSlot), "42") } returns gid
        every { ctx.nodeFor(gid) } returns returned

        val result = ctx.nodeFor<DummyNode>("42")

        assertSame(returned, result, "Extension should return the value from ctx.nodeFor(GlobalID).")
        assertSame(
            DummyNode.Reflection,
            typeSlot.captured,
            "Extension must resolve the nested object Reflection and pass it as Type<T>."
        )
    }

    @Test
    fun `globalIDFor T builds GlobalID using generated Type`() {
        val ctx = mockk<ResolverExecutionContext>()
        val gid = mockk<GlobalID<DummyNode>>()
        val typeSlot = slot<Type<DummyNode>>()

        every { ctx.globalIDFor(capture(typeSlot), "abc") } returns gid

        val result: GlobalID<DummyNode> = ctx.globalIDFor<DummyNode>("abc")

        assertSame(gid, result, "Should return the GlobalID produced by ExecutionContext.globalIDFor(Type, localId).")
        assertSame(
            DummyNode.Reflection,
            typeSlot.captured,
            "Must pass the Type<T> obtained from the nested Reflection object."
        )
    }

    @Test
    fun `nodeFor T throws a descriptive error when no nested Reflection exists`() {
        val ctx = mockk<ResolverExecutionContext>()

        val ex = assertFailsWith<Throwable> {
            ctx.nodeFor<NoReflectionNode>("id-1")
        }

        val message = ex.message ?: ""
        assertTrue(
            message.contains("Reflection") || message.contains("No nested"),
            "Exception message should mention missing nested `Reflection`."
        )
        assertTrue(
            message.contains(NoReflectionNode::class.java.name) ||
                message.contains(NoReflectionNode::class.simpleName ?: ""),
            "Exception message should mention the offending class."
        )
    }

    @Test
    fun `nodeFor T reuses the same Type instance across calls (singleton Reflection)`() {
        val ctx = mockk<ResolverExecutionContext>()
        val gid1 = mockk<GlobalID<DummyNode>>()
        val gid2 = mockk<GlobalID<DummyNode>>()
        val ret1 = DummyNode()
        val ret2 = DummyNode()

        val typeSlot1 = slot<Type<DummyNode>>()
        val typeSlot2 = slot<Type<DummyNode>>()

        every { ctx.globalIDFor(capture(typeSlot1), "1") } returns gid1
        every { ctx.nodeFor(gid1) } returns ret1

        every { ctx.globalIDFor(capture(typeSlot2), "2") } returns gid2
        every { ctx.nodeFor(gid2) } returns ret2

        val r1 = ctx.nodeFor<DummyNode>("1")
        val r2 = ctx.nodeFor<DummyNode>("2")

        assertSame(ret1, r1)
        assertSame(ret2, r2)
        assertSame(
            typeSlot1.captured,
            typeSlot2.captured,
            "Both calls should use the same Type<T> instance (Reflection is a Kotlin object)."
        )
        assertSame(
            DummyNode.Reflection,
            typeSlot1.captured,
            "The shared instance must be the nested `Reflection` object."
        )
    }
}
