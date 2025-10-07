@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime

import kotlin.Suppress
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.mocks.MockInternalContext
import viaduct.api.types.Arguments
import viaduct.engine.api.mocks.MockSchema
import viaduct.tenant.runtime.context.factory.ContextFactoryFeatureAppTest
import viaduct.tenant.runtime.context.factory.Foo_FieldWithArgs_Arguments

class GRTArgumentsConstructorExtensionsTest {
    private val contextFactory = ContextFactoryFeatureAppTest()
    private val schema = MockSchema.mk(contextFactory.sdl)

    private val internalContext = MockInternalContext(schema)

    @Test
    fun `getArgumentsGRTConstructor - success with valid Arguments type`() {
        val constructor = Foo_FieldWithArgs_Arguments::class.getArgumentsGRTConstructor()
        assertValidArgumentsGRTConstructor(constructor, Foo_FieldWithArgs_Arguments::class)
    }

    @Test
    fun `getArgumentsGRTConstructor - throws IllegalArgumentException for NoArguments`() {
        val exception = assertThrows<IllegalArgumentException> {
            Arguments.NoArguments::class.getArgumentsGRTConstructor()
        }
        assertEquals(
            "Arguments.NoArguments is a singleton object and does not have a GRT constructor.",
            exception.message
        )
    }

    @Test
    fun `getArgumentsGRTConstructor - throws IllegalArgumentException for Arguments base class`() {
        assertThrows<IllegalArgumentException> {
            Arguments::class.getArgumentsGRTConstructor()
        }
    }

    @Test
    fun `makeArgumentsGRTFactory - success with valid Arguments type`() {
        val factory = Foo_FieldWithArgs_Arguments::class.makeArgumentsGRTFactory()

        val arguments = mapOf("x" to 42, "y" to true, "z" to "test")
        val result = arguments.factory(internalContext)

        assertInstanceOf(Foo_FieldWithArgs_Arguments::class.java, result)
        assertEquals(42, result.x)
        assertEquals(true, result.y)
        assertEquals("test", result.z)
    }

    @Test
    fun `makeArgumentsGRTFactory - handles NoArguments special case`() {
        val factory = Arguments.NoArguments::class.makeArgumentsGRTFactory()

        val arguments = emptyMap<String, Any?>()
        val result = arguments.factory(internalContext)

        assertEquals(Arguments.NoArguments, result)
    }

    @Test
    fun `makeArgumentsGRTFactory - throws IllegalArgumentException for Arguments base class`() {
        assertThrows<IllegalArgumentException> {
            Arguments::class.makeArgumentsGRTFactory()
        }
    }

    @Test
    fun `makeArgumentsGRTFactory - created factory works with partial arguments`() {
        val factory = Foo_FieldWithArgs_Arguments::class.makeArgumentsGRTFactory()

        // Provide all required arguments (y is non-nullable)
        val arguments = mapOf("y" to false, "z" to "provided")
        val result = arguments.factory(internalContext)

        assertInstanceOf(Foo_FieldWithArgs_Arguments::class.java, result)
        assertEquals(null, result.x) // Int is nullable, defaults to null when not provided
        assertEquals(false, result.y)
        assertEquals("provided", result.z)
    }

    @Test
    fun `makeArgumentsGRTFactory - functional verification with different argument types`() {
        val factory = Foo_FieldWithArgs_Arguments::class.makeArgumentsGRTFactory()

        val arguments = mapOf(
            "x" to 999,
            "y" to false,
            "z" to "functional test"
        )
        val result = arguments.factory(internalContext)

        // Verify the created object functions correctly
        assertInstanceOf(Foo_FieldWithArgs_Arguments::class.java, result)
        assertEquals(999, result.x)
        assertEquals(false, result.y)
        assertEquals("functional test", result.z)
    }

    @Test
    fun `makeArgumentsGRTFactory - propagates IllegalArgumentException from getArgumentsGRTConstructor`() {
        // Create a mock invalid Arguments class for testing
        abstract class InvalidArguments : Arguments

        assertThrows<IllegalArgumentException> {
            InvalidArguments::class.makeArgumentsGRTFactory()
        }
    }

    /**
     * Validates that a constructor is a valid Arguments GRT constructor with expected signature.
     * Checks that:
     * - Constructor is not null
     * - Constructor has exactly 3 parameters
     * - Return type matches expected class
     * - Parameters have correct types (InternalContext, Map, GraphQLInputObjectType)
     */
    private fun <T : Arguments> assertValidArgumentsGRTConstructor(
        constructor: KFunction<T>?,
        expectedReturnType: KClass<T>
    ) {
        constructor ?: throw AssertionError("Constructor should not be null for valid Arguments GRT type")

        assertEquals(3, constructor.parameters.size, "Constructor should have 3 parameters")

        assertEquals(expectedReturnType, constructor.returnType.classifier, "Return type should match expected class")

        val parameterTypes = constructor.parameters.map { it.type.classifier?.toString()?.substringAfterLast(".") }
        assertEquals(
            "InternalContext",
            parameterTypes[0],
            "First parameter should be InternalContext"
        )
        assertEquals(
            "Map",
            parameterTypes[1],
            "Second parameter should be Map"
        )
        assertEquals(
            "GraphQLInputObjectType",
            parameterTypes[2],
            "Third parameter should be GraphQLInputObjectType"
        )
    }
}
