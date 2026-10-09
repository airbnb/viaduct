package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.NodeReference
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class JavaGlobalIDTest {
    private fun nodeType(name: String): Type<NodeObject> =
        object : Type<NodeObject> {
            override fun getName(): String = name

            override fun getJavaClass(): Class<out NodeObject> = NodeObject::class.java
        }

    @Test
    fun `JavaGlobalID returns the configured internal id`() {
        val gid = GlobalIDImpl<NodeObject>(type = nodeType("NodeObj"), internalId = "abc")
        assertEquals("abc", gid.getInternalID())
    }

    @Test
    fun `JavaGlobalID returns the configured Type`() {
        val type = nodeType("NodeObj")
        val gid = GlobalIDImpl<NodeObject>(type = type, internalId = "abc")
        assertSame(type, gid.getType())
        assertEquals("NodeObj", gid.getType().name)
    }

    @Test
    fun `createGlobalID extension returns a JavaGlobalID with type and id`() {
        val gid: GlobalID<NodeObject> = GlobalIDCodecDefault.createGlobalID("NodeObj", "tenant1")
        gid.shouldBeInstanceOf<GlobalIDImpl<*>>()
        assertEquals("tenant1", gid.getInternalID())
        assertEquals("NodeObj", gid.getType().name)
    }

    @Test
    fun `serializeGlobalID extension uses Type name from JavaGlobalID`() {
        val gid: GlobalID<NodeObject> = GlobalIDCodecDefault.createGlobalID("NodeObj", "tenant1")
        val serialized = GlobalIDCodecDefault.serializeGlobalID(gid)
        assertEquals(GlobalIDCodecDefault.serialize("NodeObj", "tenant1"), serialized)
    }

    @Test
    fun `serializeGlobalID extension uses Type name for non-JavaGlobalID instances`() {
        // Custom GlobalID that is not a JavaGlobalID — should still work via getType().name
        val type = nodeType("OtherType")
        val gid = object : GlobalID<NodeObject> {
            override fun getType(): Type<NodeObject> = type

            override fun getInternalID(): String = "id1"
        }
        val serialized = GlobalIDCodecDefault.serializeGlobalID(gid)
        assertEquals(GlobalIDCodecDefault.serialize("OtherType", "id1"), serialized)
    }

    @Test
    fun `class creation decoding and node context IDs share structural identity`() {
        val codec = GlobalIDCodecDefault
        val engineContext = mockk<EngineExecutionContext> {
            every { globalIDCodec } returns codec
        }
        val packageName = TestNodeObject::class.java.packageName
        val nodeContext = SimpleNodeExecutionContext(
            serializedId = codec.serialize("TestNodeObject", "abc"),
            typeName = "TestNodeObject",
            requestContext = null,
            engineExecutionContext = engineContext,
            grtPackagePrefix = packageName,
        )
        val internalContext = InternalContextImpl(mockk<EngineSchema>(), codec, packageName)
        val created = nodeContext.globalIDFor(Type.ofClass(TestNodeObject::class.java), "abc")
        val serialized = nodeContext.serialize(created)
        val equivalentIDs = listOf(
            created,
            nodeContext.deserializeGlobalID<TestNodeObject>(serialized),
            nodeContext.deserializeGlobalID<TestNodeObject>(serialized),
            internalContext.deserializeGlobalID<TestNodeObject>(serialized),
            nodeContext.getId(),
            nodeContext.getId(),
        )

        assertEquivalentIDs(equivalentIDs)
        assertNotEquals(created, nodeContext.globalIDFor(Type.ofClass(TestNodeObject::class.java), "other"))
        assertNotEquals(created, codec.createGlobalID<NodeObject>("OtherNode", "abc"))
    }

    @Test
    fun `name-only IDs have structural identity without pretending to resolve a concrete class`() {
        val codec = GlobalIDCodecDefault
        val internalContext = InternalContextImpl(mockk<EngineSchema>(), codec)
        val created = codec.createGlobalID<NodeObject>("TestNodeObject", "abc")
        val serialized = codec.serializeGlobalID(created)
        val equivalentIDs = listOf(
            created,
            internalContext.deserializeGlobalID<NodeObject>(serialized),
            internalContext.deserializeGlobalID<NodeObject>(serialized),
        )

        assertEquivalentIDs(equivalentIDs)
        assertNotEquals(created, codec.createGlobalID<NodeObject>("OtherNode", "abc"))
        assertNotEquals(created, codec.createGlobalID<NodeObject>("TestNodeObject", "other"))
        val concrete = codec.createGlobalID(Type.ofClass(TestNodeObject::class.java), "abc")
        assertNotEquals(created, concrete)
        assertNotEquals(concrete, created)
        assertNotEquals(created.getType(), concrete.getType())
        assertNotEquals(concrete.getType(), created.getType())
    }

    @Test
    fun `name-only Type matches ofClass when both represent the fallback class`() {
        val named = typeFromName<NodeObject>("NodeObject")
        val fromClass = Type.ofClass(NodeObject::class.java)

        assertEquals(fromClass, named)
        assertEquals(named, fromClass)
        assertEquals(fromClass.hashCode(), named.hashCode())
        assertNotEquals(named, null)
        assertNotEquals(named, "NodeObject")
    }

    private fun assertEquivalentIDs(ids: List<GlobalID<out NodeObject>>) {
        for (left in ids) {
            for (right in ids) {
                assertEquals(left, right)
                assertEquals(left.hashCode(), right.hashCode())
                assertEquals(left.getType(), right.getType())
                assertEquals(left.getType().hashCode(), right.getType().hashCode())
                assertEquals("found", hashMapOf(left to "found")[right])
                assertEquals(1, hashSetOf(left, right).size)
            }
        }
    }

    @Test
    fun `NodeRefWrapper exposes the wrapped NodeReference via ObjectBase`() {
        val nodeReference = mockk<NodeReference>()
        every { nodeReference.id } returns "ref-id"
        val wrapper = NodeRefWrapper(null, nodeReference)
        assertSame(nodeReference, wrapper.javaNodeReference)
        assertNull(wrapper.javaEngineObjectData)
        assertNull(wrapper.javaMapData)
    }
}
