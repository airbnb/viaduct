package viaduct.java.runtime.bridge

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Direct tests for [JavaEngineContextDelegate].
 *
 * Most of the delegate's surface — getSchema/getGlobalIDCodec/getClassFinder, deserializeGlobalID
 * (incl. TenantUsageException wrapping), globalIDFor, serialize, and globalIDStringFor's happy path
 * — is already pinned transitively by the three Simple*ContextTest suites, so it is intentionally
 * not duplicated here. This suite covers only the behaviors those suites do not reach:
 *
 *  - [nodeRef]'s new `grtClass` parameterization and its classFinder → InternalContext threading
 *    (the Simple* suites only exercise nodeRef with a null classFinder, so the context attached to
 *    the GRT is always null there).
 *  - the missing-coroutineScope throw paths of [query]/[mutation] (the Simple* suites only cover
 *    the missing-engineExecutionContext path).
 *  - globalIDStringFor's missing-engineExecutionContext throw path.
 */
class JavaEngineContextDelegateTest {
    /** A node GRT that exposes its [InternalContext] so the threaded value can be asserted on. */
    class ContextExposingNode : ObjectBase, NodeObject {
        constructor(context: InternalContext?, ref: NodeReference) : super(context, ref)

        fun exposedContext(): InternalContext? = __context()
    }

    private fun contextExposingNodeType(): Type<ContextExposingNode> =
        object : Type<ContextExposingNode> {
            override fun getName(): String = "ContextExposingNode"

            override fun getJavaClass(): Class<out ContextExposingNode> = ContextExposingNode::class.java
        }

    private fun nodeType(name: String): Type<NodeObject> =
        object : Type<NodeObject> {
            override fun getName(): String = name

            override fun getJavaClass(): Class<out NodeObject> = NodeObject::class.java
        }

    private fun engineContextResolvingNodeRef(): EngineExecutionContext {
        val gqlType = mockk<GraphQLObjectType>()
        val viaductSchema = mockk<ViaductSchema> {
            every { schema } returns mockk<GraphQLSchema> {
                every { getObjectType("ContextExposingNode") } returns gqlType
            }
        }
        return mockk {
            every { globalIDCodec } returns GlobalIDCodecDefault
            every { fullSchema } returns viaductSchema
            every { activeSchema } returns viaductSchema
            every { createNodeReference(any(), gqlType) } returns mockk<NodeReference>()
        }
    }

    @Test
    fun `nodeRef threads an InternalContext built from the classFinder onto the GRT`() {
        val engineCtx = engineContextResolvingNodeRef()
        val classFinder = mockk<ResolverClassFinder>()
        val delegate = JavaEngineContextDelegate(engineCtx, classFinder)

        val node = delegate.nodeRef(
            GlobalIDImpl(contextExposingNodeType(), "abc"),
            ContextExposingNode::class.java,
        )

        val context = node.exposedContext()
        context.shouldBeInstanceOf<InternalContext>()
        assertSame(classFinder, context.classFinder)
    }

    @Test
    fun `nodeRef threads a null context onto the GRT when no classFinder is present`() {
        val engineCtx = engineContextResolvingNodeRef()
        val delegate = JavaEngineContextDelegate(engineCtx, classFinder = null)

        val node = delegate.nodeRef(
            GlobalIDImpl(contextExposingNodeType(), "abc"),
            ContextExposingNode::class.java,
        )

        assertNull(node.exposedContext())
    }

    @Test
    fun `query throws FrameworkException when a coroutineScope is missing`() {
        val engineCtx = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns GlobalIDCodecDefault
        }
        val delegate = JavaEngineContextDelegate(engineCtx, classFinder = null, coroutineScope = null)

        val ex = assertThrows<FrameworkException> {
            delegate.query("{ id }", emptyMap(), Any::class.java)
        }
        assertTrue(ex.message!!.contains("requires a coroutineScope"))
    }

    @Test
    fun `mutation throws FrameworkException when a coroutineScope is missing`() {
        val engineCtx = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns GlobalIDCodecDefault
        }
        val delegate = JavaEngineContextDelegate(engineCtx, classFinder = null, coroutineScope = null)

        val ex = assertThrows<FrameworkException> {
            delegate.mutation("{ id }", emptyMap(), Any::class.java)
        }
        assertTrue(ex.message!!.contains("requires a coroutineScope"))
    }

    @Test
    fun `globalIDStringFor throws FrameworkException when engineExecutionContext is null`() {
        val delegate = JavaEngineContextDelegate(engineExecutionContext = null)

        val ex = assertThrows<FrameworkException> {
            delegate.globalIDStringFor(nodeType("NodeObj"), "abc")
        }
        assertTrue(ex.message!!.contains("globalIDStringFor requires engineExecutionContext"))
    }

    @Test
    fun `globalIDStringFor returns the codec-serialized form`() {
        val engineCtx = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns GlobalIDCodecDefault
        }
        val delegate = JavaEngineContextDelegate(engineCtx)

        val serialized: String = delegate.globalIDStringFor(nodeType("NodeObj"), "abc")

        assertEquals(GlobalIDCodecDefault.serialize("NodeObj", "abc"), serialized)
    }
}
