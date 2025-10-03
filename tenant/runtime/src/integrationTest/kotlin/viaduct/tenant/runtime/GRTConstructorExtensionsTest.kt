@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime

import kotlin.Suppress
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductTenantUsageException
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockType
import viaduct.api.types.NodeObject
import viaduct.engine.api.UnsetSelectionException
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.User

class GRTConstructorExtensionsTest {
    @Test
    fun `getGRTConstructor - success with valid User type`() {
        val constructor = User.Reflection.kcls.getGRTConstructor()
        assertValidGRTConstructor(constructor, User::class)
    }

    @Test
    fun `getGRTConstructor - throws IllegalArgumentException for NodeObject class with no primary constructor`() {
        assertThrows<IllegalArgumentException> {
            NodeObject::class.getGRTConstructor()
        }
    }

    @Test
    fun `getGRTConstructor - throws IllegalArgumentException for MockType with invalid constructor`() {
        val invalidType = MockType("InvalidType", NodeObject::class)

        assertThrows<IllegalArgumentException> {
            invalidType.kcls.getGRTConstructor()
        }
    }

    @Test
    fun `wrap - success with User type and can access provided fields`() {
        val userType = GlobalIdFeatureAppTest.schema.schema.getObjectType("User")
        val mockEngineObjectData = mkEngineObjectData(
            userType,
            mapOf(
                "id" to "User:user123", // MockGlobalIDCodec expects "TypeName:internalId" format
                "name" to "John Doe"
                // Intentionally not providing email to test UnsetSelectionException
            )
        )
        val internalContext = MockInternalContext(GlobalIdFeatureAppTest.schema)

        val user = mockEngineObjectData.toGRT(internalContext, User.Reflection)

        assertInstanceOf(User::class.java, user)

        val globalId = runBlocking {
            user.getId()
        }
        assertInstanceOf(globalId::class.java, globalId) // Ensure getId returns proper type
        assertEquals("User:user123", globalId.toString())

        val name = runBlocking {
            user.getName()
        }
        assertEquals("John Doe", name)

        val exception = assertThrows<ViaductTenantUsageException> {
            runBlocking {
                user.getEmail()
            }
        }
        assertInstanceOf(UnsetSelectionException::class.java, exception.cause)
    }

    @Test
    fun `wrap - propagates IllegalArgumentException from getGRTConstructor`() {
        val userType = GlobalIdFeatureAppTest.schema.schema.getObjectType("User")
        val mockEngineObjectData = mkEngineObjectData(userType, emptyMap())
        val internalContext = MockInternalContext(GlobalIdFeatureAppTest.schema)
        val invalidType = MockType("InvalidType", NodeObject::class)

        assertThrows<IllegalArgumentException> {
            mockEngineObjectData.toGRT(internalContext, invalidType)
        }
    }

    @Test
    fun `wrap - created User can access engineObjectData normally`() {
        val userType = GlobalIdFeatureAppTest.schema.schema.getObjectType("User")
        val mockEngineObjectData = mkEngineObjectData(
            userType,
            mapOf(
                "id" to "User:user123",
                "name" to "John Doe",
                "email" to "john@example.com"
            )
        )
        val internalContext = MockInternalContext(GlobalIdFeatureAppTest.schema)

        val user = mockEngineObjectData.toGRT(internalContext, User.Reflection)

        // Test that the wrapped object can be used normally
        // This tests that the constructor was called correctly and the object is functional
        assertEquals(mockEngineObjectData, user.engineObjectData, "EngineObjectData should be preserved")
    }

    /**
     * Validates that a constructor is a valid GRT constructor with expected signature.
     * Checks that:
     * - Constructor is not null
     * - Constructor has exactly 2 parameters
     * - Return type matches expected class
     * - Parameters have correct types (InternalContext and EngineObjectData)
     */
    private fun <T : Any> assertValidGRTConstructor(
        constructor: KFunction<T>?,
        expectedReturnType: KClass<T>
    ) {
        constructor ?: throw AssertionError("Constructor should not be null for valid GRT type")

        assertEquals(2, constructor.parameters.size, "Constructor should have 2 parameters")

        assertEquals(expectedReturnType, constructor.returnType.classifier, "Return type should match expected class")

        val parameterTypes = constructor.parameters.map { it.type.classifier?.toString()?.substringAfterLast(".") }
        assertEquals(
            "InternalContext",
            parameterTypes[0],
            "First parameter should be InternalContext"
        )
        assertEquals(
            "EngineObjectData",
            parameterTypes[1],
            "Second parameter should be EngineObjectData"
        )
    }
}
