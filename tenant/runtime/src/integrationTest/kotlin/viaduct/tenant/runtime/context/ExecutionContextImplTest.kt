package viaduct.tenant.runtime.context

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.reflect.KClass
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.FieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.mocks.MockInternalContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault as DefaultCodec
import viaduct.tenant.runtime.globalid.GlobalIDImpl
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.User

@ExperimentalCoroutinesApi
class ExecutionContextImplTest : ContextTestBase() {
    private fun mk(
        obj: Object = Obj,
        query: Query = Q,
        args: Arguments = Args,
        globalIDCodec: GlobalIDCodec = DefaultCodec,
        selectionSet: SelectionSet<CompositeOutput> = noSelections,
    ): FieldExecutionContext<Object, Query, Arguments, CompositeOutput> =
        FieldExecutionContextImpl(
            MockInternalContext(GlobalIdFeatureAppTest.schema, globalIDCodec),
            EngineExecutionContextWrapperImpl(ContextMocks(GlobalIdFeatureAppTest.schema).engineExecutionContext),
            selectionSet,
            null, // requestContext
            args,
            obj,
            query,
        )

    @Test
    fun `globalIDFor - valid type and id returns GlobalID`() {
        val ctx = mk()

        val result = ctx.globalIDFor(User.Reflection, "123")

        assertEquals(User.Reflection, result.type)
        assertEquals("123", result.internalID)
        assertTrue(result is GlobalIDImpl<*>)
    }

    @Test
    fun `globalIDFor - type kcls constructor mismatch should throw with reasonable error`() {
        val ctx = mk()

        @Suppress("UNCHECKED_CAST")
        val fakeTypeWithWrongKClass = object : Type<NodeObject> {
            override val name: String = "User"
            override val kcls: KClass<out NodeObject> = String::class as KClass<out NodeObject>
        }

        val exception = assertThrows<IllegalArgumentException> {
            ctx.globalIDFor(fakeTypeWithWrongKClass, "123")
        }

        assertTrue(
            exception.message?.contains("NodeObject") == true,
            "Error message should mention 'NodeObject': ${exception.message}"
        )
    }

    @Test
    fun `globalIDFor - empty internal id creates valid GlobalID`() {
        val ctx = mk()

        val result = ctx.globalIDFor(User.Reflection, "")

        assertEquals(User.Reflection, result.type)
        assertEquals("", result.internalID)
        assertTrue(result is GlobalIDImpl<*>)
    }

    @Test
    fun `globalIDFor - special characters in internal id are preserved`() {
        val ctx = mk()

        val specialInternalId = "user:123%+test value!@#$%^&*()"

        val result = ctx.globalIDFor(User.Reflection, specialInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(specialInternalId, result.internalID)
        assertTrue(result is GlobalIDImpl<*>)
    }

    @Test
    fun `globalIDFor - unicode characters in internal id are preserved`() {
        val ctx = mk()

        val unicodeInternalId = "用户_测试_🚀_123"

        val result = ctx.globalIDFor(User.Reflection, unicodeInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(unicodeInternalId, result.internalID)
        assertTrue(result is GlobalIDImpl<*>)
    }

    @Test
    fun `globalIDFor - whitespace only internal id creates valid GlobalID`() {
        val ctx = mk()

        val whitespaceInternalId = "   \t\n  "

        val result = ctx.globalIDFor(User.Reflection, whitespaceInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(whitespaceInternalId, result.internalID)
        assertTrue(result is GlobalIDImpl<*>)
    }

    @Test
    fun `globalIDFor - very long internal id creates valid GlobalID`() {
        val ctx = mk()

        val longInternalId = "a".repeat(10000)

        val result = ctx.globalIDFor(User.Reflection, longInternalId)

        assertEquals(User.Reflection, result.type)
        assertEquals(longInternalId, result.internalID)
        assertEquals(10000, result.internalID.length)
        assertTrue(result is GlobalIDImpl<*>)
    }

    @Test
    fun `globalIDFor - different types with same internal id produce different GlobalIDs`() {
        val ctx = mk()

        val internalId = "123"
        val userGlobalId = ctx.globalIDFor(User.Reflection, internalId)

        assertEquals(User.Reflection, userGlobalId.type)
        assertEquals(internalId, userGlobalId.internalID)
    }

    @Test
    fun `globalIDFor - same type and internal id produce equal GlobalIDs`() {
        val ctx = mk()

        val internalId = "123"
        val globalId1: GlobalID<User> = ctx.globalIDFor(User.Reflection, internalId)
        val globalId2: GlobalID<User> = ctx.globalIDFor(User.Reflection, internalId)

        assertEquals(globalId1, globalId2)
        assertEquals(globalId1.hashCode(), globalId2.hashCode())
        assertEquals(globalId1.type, globalId2.type)
        assertEquals(globalId1.internalID, globalId2.internalID)
    }

    @Test
    fun `globalIDStringFor - valid type and id returns serialized string`() {
        val mockGlobalIDCodec = mockk<GlobalIDCodec>()
        val ctx = mk(globalIDCodec = mockGlobalIDCodec)

        val expectedSerializedString = "encoded_user_123"

        every {
            mockGlobalIDCodec.serialize("User", "123")
        } returns expectedSerializedString

        val result = ctx.globalIDStringFor(User.Reflection, "123")

        assertEquals(expectedSerializedString, result)

        verify {
            mockGlobalIDCodec.serialize("User", "123")
        }
    }

    @Test
    fun `globalIDStringFor - internalID contains characters that require escaping`() {
        val ctx = mk(globalIDCodec = GlobalIDCodecDefault)

        val internalIdWithSpecialChars = "user:123%+test value"

        val result = ctx.globalIDStringFor(User.Reflection, internalIdWithSpecialChars)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        // GlobalIDCodecDefault uses Base64 encoding, which doesn't contain these characters
        assertFalse(result.contains(":"))
        assertFalse(result.contains("%"))
        assertFalse(result.contains("+"))
        assertFalse(result.contains(" "))
    }

    @Test
    fun `globalIDStringFor - returned value can be decoded using GlobalIDCodecDefault`() {
        val ctx = mk(globalIDCodec = GlobalIDCodecDefault)

        val originalInternalId = "user:123%+test value"

        val encodedString = ctx.globalIDStringFor(User.Reflection, originalInternalId)

        val (decodedTypeName, decodedInternalId) = GlobalIDCodecDefault.deserialize(encodedString)

        assertEquals(User.Reflection.name, decodedTypeName)
        assertEquals(originalInternalId, decodedInternalId)
    }

    @Test
    fun `globalIDStringFor - round trip encoding with various special characters`() {
        val ctx = mk(globalIDCodec = GlobalIDCodecDefault)

        val testCases = listOf(
            "simple123",
            "user:with:colons",
            "percent%encoded",
            "plus+signs",
            "spaces in id",
            "symbols!@#$%^&*()",
            "unicode_测试_🚀",
            "mixed:123%test+with spaces&symbols",
            "",
            "   ",
        )

        testCases.forEach { originalInternalId ->
            val encodedString = ctx.globalIDStringFor(User.Reflection, originalInternalId)

            val (decodedTypeName, decodedInternalId) = GlobalIDCodecDefault.deserialize(encodedString)

            assertEquals(
                originalInternalId,
                decodedInternalId,
                "Round-trip failed for internal ID: '$originalInternalId'"
            )
            assertEquals(User.Reflection.name, decodedTypeName)
        }
    }
}
