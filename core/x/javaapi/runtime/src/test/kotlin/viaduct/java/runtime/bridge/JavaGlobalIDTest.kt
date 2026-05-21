package viaduct.java.runtime.bridge

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        assertThat(gid.getInternalID()).isEqualTo("abc")
    }

    @Test
    fun `JavaGlobalID returns the configured Type`() {
        val type = nodeType("NodeObj")
        val gid = GlobalIDImpl<NodeObject>(type = type, internalId = "abc")
        assertThat(gid.getType()).isSameAs(type)
        assertThat(gid.getType().name).isEqualTo("NodeObj")
    }

    @Test
    fun `createGlobalID extension returns a JavaGlobalID with type and id`() {
        val gid: GlobalID<NodeObject> = GlobalIDCodecDefault.createGlobalID("NodeObj", "tenant1")
        assertThat(gid).isInstanceOf(GlobalIDImpl::class.java)
        assertThat(gid.getInternalID()).isEqualTo("tenant1")
        assertThat(gid.getType().name).isEqualTo("NodeObj")
    }

    @Test
    fun `serializeGlobalID extension uses Type name from JavaGlobalID`() {
        val gid: GlobalID<NodeObject> = GlobalIDCodecDefault.createGlobalID("NodeObj", "tenant1")
        val serialized = GlobalIDCodecDefault.serializeGlobalID(gid)
        assertThat(serialized).isEqualTo(GlobalIDCodecDefault.serialize("NodeObj", "tenant1"))
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
        assertThat(serialized).isEqualTo(GlobalIDCodecDefault.serialize("OtherType", "id1"))
    }

    @Test
    fun `NodeRefWrapper exposes the wrapped NodeReference via ObjectBase`() {
        val nodeReference = mockk<NodeReference>()
        every { nodeReference.id } returns "ref-id"
        val wrapper = NodeRefWrapper(nodeReference)
        assertThat(wrapper.javaNodeReference).isSameAs(nodeReference)
        assertThat(wrapper.javaEngineObjectData).isNull()
        assertThat(wrapper.javaMapData).isNull()
    }
}
