package viaduct.java.runtime.bridge

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
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
        assertThat(id.getInternalID()).isEqualTo("tenant1")
        assertThat(id).isInstanceOf(GlobalIDImpl::class.java)
        assertThat(id.getType().name).isEqualTo("NodeObj")
    }

    @Test
    fun `getRequestContext returns the provided value`() {
        val ctx = newContext(requestContext = "ctx-value")
        assertThat(ctx.getRequestContext()).isEqualTo("ctx-value")
    }

    @Test
    fun `getRequestContext returns null when none provided`() {
        assertThat(newContext().getRequestContext()).isNull()
    }

    @Test
    fun `globalIDFor creates a typed GlobalID from type name and internal id`() {
        val ctx = newContext()
        val gid: GlobalID<NodeObject> = ctx.globalIDFor(nodeType(), "abc")
        assertThat(gid.getInternalID()).isEqualTo("abc")
        assertThat(gid).isInstanceOf(GlobalIDImpl::class.java)
    }

    @Test
    fun `serialize returns serialized form for a GlobalIDImpl`() {
        val ctx = newContext()
        val gid = ctx.globalIDFor(nodeType(), "xyz")
        assertThat(ctx.serialize(gid)).isEqualTo(GlobalIDCodecDefault.serialize("NodeObj", "xyz"))
    }

    @Test
    fun `globalIDStringFor returns serialized form using the provided Type`() {
        val ctx = newContext()
        assertThat(ctx.globalIDStringFor(nodeType("NodeObj"), "abc"))
            .isEqualTo(GlobalIDCodecDefault.serialize("NodeObj", "abc"))
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
        assertThat(ex.message).contains("GraphQL type 'Missing' not found in schema")
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

        assertThat(result).isInstanceOf(TestNodeObject::class.java)
        assertThat((result as TestNodeObject).javaNodeReference).isSameAs(nodeRef)
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
        assertThat(ctx.getSchema()).isSameAs(schema)
    }

    @Test
    fun `getSchema throws when engineExecutionContext is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> { ctx.getSchema() }
        assertThat(ex.message).contains("engineExecutionContext")
    }

    @Test
    fun `getGlobalIDCodec returns codec from engineExecutionContext`() {
        val codec = mockk<GlobalIDCodec>()
        val engineCtx = mockk<EngineExecutionContext> {
            every { requestContext } returns null
            every { globalIDCodec } returns codec
        }
        val ctx = newContext(engineCtx = engineCtx)
        assertThat(ctx.getGlobalIDCodec()).isSameAs(codec)
    }

    @Test
    fun `getGlobalIDCodec throws when engineExecutionContext is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> { ctx.getGlobalIDCodec() }
        assertThat(ex.message).contains("engineExecutionContext")
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
        assertThat(ctx.getClassFinder()).isSameAs(finder)
    }

    @Test
    fun `getClassFinder throws when classFinder is null`() {
        val ctx = newContext(engineCtx = null)
        val ex = assertThrows<FrameworkException> { ctx.getClassFinder() }
        assertThat(ex.message).contains("classFinder")
    }
}
