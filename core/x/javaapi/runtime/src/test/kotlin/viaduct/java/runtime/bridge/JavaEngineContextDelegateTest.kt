package viaduct.java.runtime.bridge

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.java.api.internal.InputBase
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.reflect.RootObjectField
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Direct tests for [JavaEngineContextDelegate].
 *
 * Most of the delegate's surface — getSchema/getGlobalIDCodec, deserializeGlobalID
 * (incl. TenantUsageException wrapping), globalIDFor, serialize, and globalIDStringFor's happy path
 * — is already pinned transitively by the three Simple*ContextTest suites, so it is intentionally
 * not duplicated here. This suite covers only the behaviors those suites do not reach:
 *
 *  - [nodeRef]'s `grtClass` parameterization and its InternalContext threading.
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

    class RootParent : GraphQLObject

    class RootResult(
        context: InternalContext?,
        rootFieldReference: RootFieldReference,
    ) : ObjectBase(context, rootFieldReference)

    class RootArguments(data: Map<String, Any?>) :
        InputBase(null, data, null),
        Arguments

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
    fun `nodeRef attaches an InternalContext to the GRT`() {
        val engineCtx = engineContextResolvingNodeRef()
        val delegate = JavaEngineContextDelegate(engineCtx)

        val node = delegate.nodeRef(
            GlobalIDImpl(contextExposingNodeType(), "abc"),
            ContextExposingNode::class.java,
        )

        val context = node.exposedContext()
        context.shouldBeInstanceOf<InternalContext>()
        assertSame(engineCtx.fullSchema, context.schema)
        assertSame(engineCtx.globalIDCodec, context.globalIDCodec)
    }

    @Test
    fun `rootFieldRef uses the full schema and forwards namespace path and typed arguments`() {
        val graphqlType = mockk<GraphQLObjectType>()
        val rootFieldReference = mockk<RootFieldReference>()
        val fullGraphqlSchema = mockk<GraphQLSchema> {
            every { getObjectType("RootResult") } returns graphqlType
        }
        val fullViaductSchema = mockk<ViaductSchema> {
            every { schema } returns fullGraphqlSchema
        }
        val activeViaductSchema = mockk<ViaductSchema> {
            every { schema } returns mockk<GraphQLSchema> {
                every { getObjectType("RootResult") } returns null
            }
        }
        val engineCtx = mockk<EngineExecutionContext> {
            every { fullSchema } returns fullViaductSchema
            every { activeSchema } returns activeViaductSchema
            every { globalIDCodec } returns GlobalIDCodecDefault
            every {
                createRootFieldReference(
                    listOf("_factories", "products", "create"),
                    graphqlType,
                    mapOf("name" to "Widget"),
                )
            } returns rootFieldReference
        }
        val field: RootObjectField<RootParent, RootResult, RootArguments> = RootObjectField.of(
            "create",
            Type.ofClass(RootParent::class.java),
            Type.ofClass(RootResult::class.java),
            listOf("_factories", "products", "create"),
        )

        val result: RootResult = JavaEngineContextDelegate(engineCtx).rootFieldRef(
            field,
            RootArguments(mapOf("name" to "Widget")),
        )

        assertSame(rootFieldReference, result.javaRootFieldReference)
        verify(exactly = 1) {
            engineCtx.createRootFieldReference(
                listOf("_factories", "products", "create"),
                graphqlType,
                mapOf("name" to "Widget"),
            )
        }
    }

    @Test
    fun `query throws FrameworkException when a coroutineScope is missing`() {
        val engineCtx = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns GlobalIDCodecDefault
        }
        val delegate = JavaEngineContextDelegate(engineCtx, grtPackagePrefix = null, coroutineScope = null)

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
        val delegate = JavaEngineContextDelegate(engineCtx, grtPackagePrefix = null, coroutineScope = null)

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
