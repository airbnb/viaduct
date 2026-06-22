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
import viaduct.errors.TenantUsageException
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class TestNodeObject : ObjectBase, NodeObject {
    constructor(context: InternalContext?, ref: NodeReference) : super(context, ref)
}

class SimpleNodeExecutionContextTest {
    private fun mockEngineContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
        }

    private fun newContext(
        serializedId: String = GlobalIDCodecDefault.serialize("NodeObj", "tenant1"),
        typeName: String = "NodeObj",
        requestContext: Any? = null,
        engineCtx: EngineExecutionContext? = mockEngineContext(),
    ) = SimpleNodeExecutionContext(
        serializedId = serializedId,
        typeName = typeName,
        requestContext = requestContext,
        engineExecutionContext = engineCtx,
    )

    private fun nodeType(name: String = "NodeObj"): Type<NodeObject> =
        object : Type<NodeObject> {
            override fun getName(): String = name

            override fun getJavaClass(): Class<out NodeObject> = NodeObject::class.java
        }

    @Test
    fun `getId deserializes the serialized id and returns a GlobalIDImpl`() {
        val ctx = newContext()
        val id = ctx.getId()
        assertEquals("tenant1", id.getInternalID())
        id.shouldBeInstanceOf<GlobalIDImpl<*>>()
        assertEquals("NodeObj", id.getType().name)
    }

    @Test
    fun `getRequestContext returns the provided value`() {
        val ctx = newContext(requestContext = "ctx-value")
        assertEquals("ctx-value", ctx.getRequestContext())
    }

    @Test
    fun `getRequestContext returns null when none provided`() {
        assertNull(newContext().getRequestContext())
    }

    @Test
    fun `globalIDFor creates a typed GlobalID from type name and internal id`() {
        val ctx = newContext()
        val gid: GlobalID<NodeObject> = ctx.globalIDFor(nodeType(), "abc")
        assertEquals("abc", gid.getInternalID())
        gid.shouldBeInstanceOf<GlobalIDImpl<*>>()
    }

    @Test
    fun `serialize returns serialized form for a GlobalIDImpl`() {
        val ctx = newContext()
        val gid = ctx.globalIDFor(nodeType(), "xyz")
        assertEquals(GlobalIDCodecDefault.serialize("NodeObj", "xyz"), ctx.serialize(gid))
    }

    @Test
    fun `globalIDStringFor returns serialized form using the provided Type`() {
        val ctx = newContext()
        assertEquals(GlobalIDCodecDefault.serialize("NodeObj", "abc"), ctx.globalIDStringFor(nodeType("NodeObj"), "abc"))
    }

    @Test
    fun `deserializeGlobalID deserializes the serialized id into a typed GlobalID`() {
        val ctx = newContext()
        val gid: GlobalID<NodeObject> =
            ctx.deserializeGlobalID(GlobalIDCodecDefault.serialize("NodeObj", "tenant1"))
        gid.shouldBeInstanceOf<GlobalIDImpl<*>>()
        assertEquals("tenant1", gid.getInternalID())
        assertEquals("NodeObj", gid.getType().name)
    }

    @Test
    fun `deserializeGlobalID throws FrameworkException when engineExecutionContext is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> {
            ctx.deserializeGlobalID<NodeObject>(GlobalIDCodecDefault.serialize("NodeObj", "tenant1"))
        }
        assertTrue(ex.message!!.contains("deserializeGlobalID requires engineExecutionContext"))
    }

    @Test
    fun `deserializeGlobalID wraps codec IllegalArgumentException in TenantUsageException`() {
        val ctx = newContext()
        val ex = assertThrows<TenantUsageException> {
            ctx.deserializeGlobalID<NodeObject>("not-valid-base64!!!")
        }
        assertTrue(ex.message!!.contains("Invalid GlobalID"))
        ex.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `nodeRef throws FrameworkException when engineExecutionContext is missing`() {
        val ctx = newContext(engineCtx = null)
        assertThrows<FrameworkException> { ctx.nodeRef(GlobalIDImpl(nodeType(), "abc")) }
    }

    @Test
    fun `nodeRef throws FrameworkException when GraphQL type not found in schema`() {
        val viaductSchema = mockk<ViaductSchema> {
            every { schema } returns mockk<GraphQLSchema> {
                every { getObjectType(any()) } returns null
            }
        }
        val engineCtx = mockk<EngineExecutionContext> {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
            every { activeSchema } returns viaductSchema
        }
        val ctx = newContext(engineCtx = engineCtx)

        val ex = assertThrows<FrameworkException> {
            ctx.nodeRef(GlobalIDImpl(nodeType("Missing"), "abc"))
        }
        assertTrue(ex.message!!.contains("GraphQL type 'Missing' not found in schema"))
    }

    @Test
    fun `nodeRef constructs the typed Java GRT instance from the GlobalID`() {
        val nodeRef = mockk<NodeReference>()
        val gqlType = mockk<GraphQLObjectType>()
        val viaductSchema = mockk<ViaductSchema> {
            every { schema } returns mockk<GraphQLSchema> {
                every { getObjectType("TestNodeObject") } returns gqlType
            }
        }
        val engineCtx = mockk<EngineExecutionContext> {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
            every { activeSchema } returns viaductSchema
            every { createNodeReference(any(), gqlType) } returns nodeRef
        }
        val ctx = newContext(engineCtx = engineCtx)
        val typedType: Type<NodeObject> =
            object : Type<NodeObject> {
                override fun getName(): String = "TestNodeObject"

                override fun getJavaClass(): Class<out NodeObject> = TestNodeObject::class.java
            }

        val result: NodeObject = ctx.nodeRef(GlobalIDImpl(typedType, "abc"))

        result.shouldBeInstanceOf<TestNodeObject>()
        assertSame(nodeRef, result.javaNodeReference)
    }

    @Test
    fun `query throws FrameworkException when engineExecutionContext is missing`() {
        val ctx = newContext(engineCtx = null)
        assertThrows<FrameworkException> { ctx.query("{ id }", emptyMap(), Any::class.java) }
    }

    @Test
    fun `mutation throws FrameworkException when engineExecutionContext is missing`() {
        val ctx = newContext(engineCtx = null)
        assertThrows<FrameworkException> { ctx.mutation("{ id }", emptyMap(), Any::class.java) }
    }

    @Test
    fun `selections throws FrameworkException for not yet implemented`() {
        // SelectiveNodeExecutionContext — selections() is a placeholder until we wire in
        // SelectionSet support for Java. Mirrors SimpleFieldExecutionContext.getSelections.
        val ctx = newContext()
        assertThrows<FrameworkException> { ctx.selections() }
    }

    // ── InternalContext tests ──

    @Test
    fun `getSchema returns schema from engineExecutionContext`() {
        val schema = mockk<ViaductSchema>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
            every { fullSchema } returns schema
        }
        val ctx = newContext(engineCtx = engineCtx)
        assertSame(schema, ctx.getSchema())
    }

    @Test
    fun `getSchema throws when engineExecutionContext is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> { ctx.getSchema() }
        assertTrue(ex.message!!.contains("engineExecutionContext"))
    }

    @Test
    fun `getGlobalIDCodec returns codec from engineExecutionContext`() {
        val codec = mockk<GlobalIDCodec>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { requestContext } returns null
            every { globalIDCodec } returns codec
        }
        val ctx = newContext(engineCtx = engineCtx)
        assertSame(codec, ctx.getGlobalIDCodec())
    }

    @Test
    fun `getGlobalIDCodec throws when engineExecutionContext is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> { ctx.getGlobalIDCodec() }
        assertTrue(ex.message!!.contains("engineExecutionContext"))
    }

    @Test
    fun `getClassFinder returns provided classFinder`() {
        val finder = mockk<ResolverClassFinder>()
        val ctx = SimpleNodeExecutionContext(
            serializedId = GlobalIDCodecDefault.serialize("NodeObj", "tenant1"),
            typeName = "NodeObj",
            requestContext = null,
            engineExecutionContext = mockEngineContext(),
            classFinder = finder
        )
        assertSame(finder, ctx.getClassFinder())
    }

    @Test
    fun `getClassFinder throws when classFinder is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> { ctx.getClassFinder() }
        assertTrue(ex.message!!.contains("classFinder"))
    }
}
