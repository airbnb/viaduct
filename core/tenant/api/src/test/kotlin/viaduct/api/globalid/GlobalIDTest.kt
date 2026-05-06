package viaduct.api.globalid

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject

class GlobalIDTest {
    class Foo : NodeObject

    class Bar : NodeObject

    class NotNode : CompositeOutput

    @Test
    fun `equals returns true for same type and internalID`() {
        val fooID1 = GlobalID(Type.Companion.ofClass(Foo::class), "123")
        val fooID2 = GlobalID(Type.Companion.ofClass(Foo::class), "123")
        assertEquals(fooID1, fooID2)
    }

    @Test
    fun `equals returns false for different types`() {
        val fooID = GlobalID(Type.Companion.ofClass(Foo::class), "123")
        val barID = GlobalID(Type.Companion.ofClass(Bar::class), "123")
        assertFalse(fooID.equals(barID))
    }

    @Test
    fun `equals returns false for different internalIDs`() {
        val fooID1 = GlobalID(Type.Companion.ofClass(Foo::class), "123")
        val fooID2 = GlobalID(Type.Companion.ofClass(Foo::class), "456")
        assertNotEquals(fooID1, fooID2)
    }

    @Test
    fun `equals returns false when comparing with non-GlobalID instance`() {
        val fooID = GlobalID(Type.Companion.ofClass(Foo::class), "123")
        val someID = "Some String"
        assertFalse(fooID.equals(someID))
    }

    @Test
    fun `throws exception when type is not a concrete node object`() {
        assertFailsWith<IllegalArgumentException> {
            // This should fail at runtime because NotNode doesn't extend NodeObject
            // We need to suppress the unchecked cast warning because we're intentionally
            // testing the runtime check
            @Suppress("UNCHECKED_CAST")
            val notNodeType = Type.Companion.ofClass(NotNode::class) as Type<NodeObject>
            GlobalID(notNodeType, "123")
        }
    }
}
