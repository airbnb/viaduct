package viaduct.api.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.MockType
import viaduct.api.mocks.testGlobalId
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class GlobalIDCodecTest {
    @Test
    fun `serialize delegates to service codec`() {
        val type = MockType.mkNodeObject("TestType")
        val globalId = GlobalIDImpl(type, "internal-123")
        val reflectionLoader = MockReflectionLoader(type)

        val codec = GlobalIDCodec(GlobalIDCodecDefault, reflectionLoader)
        val serialized = codec.serialize(globalId)

        assertEquals(type.testGlobalId("internal-123"), serialized)
    }

    @Test
    fun `deserialize reconstructs GlobalID with proper type`() {
        val type = MockType.mkNodeObject("TestType")
        val reflectionLoader = MockReflectionLoader(type)

        val codec = GlobalIDCodec(GlobalIDCodecDefault, reflectionLoader)
        val serialized = type.testGlobalId("internal-123")
        val deserialized = codec.deserialize<NodeObject>(serialized)

        assertEquals("TestType", deserialized.type.name)
        assertEquals("internal-123", deserialized.internalID)
    }

    @Test
    fun `serialize and deserialize roundtrip`() {
        val type = MockType.mkNodeObject("User")
        val reflectionLoader = MockReflectionLoader(type)
        val codec = GlobalIDCodec(GlobalIDCodecDefault, reflectionLoader)

        val originalId = GlobalIDImpl(type, "user-456")
        val serialized = codec.serialize(originalId)
        val deserialized = codec.deserialize<NodeObject>(serialized)

        assertEquals(originalId.type.name, deserialized.type.name)
        assertEquals(originalId.internalID, deserialized.internalID)
    }

    @Test
    fun `deserialize throws for non-NodeObject type`() {
        val nonNodeType = MockType("NonNodeType", Object::class)
        val reflectionLoader = MockReflectionLoader(nonNodeType)

        val codec = GlobalIDCodec(GlobalIDCodecDefault, reflectionLoader)

        val serialized = GlobalIDCodecDefault.serialize("NonNodeType", "id-123")
        val exception = assertThrows<IllegalArgumentException> {
            codec.deserialize<NodeObject>(serialized)
        }
        assertEquals("type `NonNodeType` from GlobalID '$serialized' is not a NodeObject", exception.message)
    }

    @Test
    fun `deserialize with different internal IDs`() {
        val type = MockType.mkNodeObject("Product")
        val reflectionLoader = MockReflectionLoader(type)
        val codec = GlobalIDCodec(GlobalIDCodecDefault, reflectionLoader)

        val id1 = codec.deserialize<NodeObject>(type.testGlobalId("abc"))
        val id2 = codec.deserialize<NodeObject>(type.testGlobalId("xyz"))

        assertEquals("abc", id1.internalID)
        assertEquals("xyz", id2.internalID)
        assertEquals(id1.type.name, id2.type.name)
    }
}
