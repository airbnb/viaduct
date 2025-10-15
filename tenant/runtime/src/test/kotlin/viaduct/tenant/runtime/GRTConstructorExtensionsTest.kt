package viaduct.tenant.runtime

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineObjectData

class GRTConstructorExtensionsTest {
    // Test classes that don't follow GRT constructor conventions
    class InvalidObjectNoConstructor private constructor() : CompositeOutput

    class InvalidObjectWrongParams(val value: String) : CompositeOutput

    class InvalidObjectOneParam(ctx: InternalContext) : CompositeOutput

    class InvalidObjectThreeParams(
        ctx: InternalContext,
        eod: EngineObjectData,
        extra: String
    ) : CompositeOutput

    class InvalidArgumentsNoConstructor private constructor() : Arguments

    class InvalidArgumentsWrongParams(val value: String) : Arguments

    class InvalidArgumentsTwoParams(
        ctx: InternalContext,
        args: Map<String, Any?>
    ) : Arguments

    class InvalidArgumentsFourParams(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: graphql.schema.GraphQLInputObjectType,
        extra: String
    ) : Arguments

    // Valid GRT constructors for positive testing
    class ValidObject(
        ctx: InternalContext,
        eod: EngineObjectData
    ) : CompositeOutput

    @Suppress("UNUSED_PARAMETER")
    class ValidArguments(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: graphql.schema.GraphQLInputObjectType
    ) : Arguments

    @Test
    fun `getGRTConstructor with valid Object constructor succeeds`() {
        val constructor = ValidObject::class.getGRTConstructor()
        assertNotNull(constructor)
    }

    @Test
    fun `getGRTConstructor with no primary constructor throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidObjectNoConstructor::class.getGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getGRTConstructor with wrong parameter types throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidObjectWrongParams::class.getGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getGRTConstructor with one parameter throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidObjectOneParam::class.getGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getGRTConstructor with three parameters throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidObjectThreeParams::class.getGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getArgumentsGRTConstructor with valid Arguments constructor succeeds`() {
        val constructor = ValidArguments::class.getArgumentsGRTConstructor()
        assertNotNull(constructor)
    }

    @Test
    fun `getArgumentsGRTConstructor with NoArguments throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            Arguments.NoArguments::class.getArgumentsGRTConstructor()
        }
        assertTrue(exception.message?.contains("NoArguments") ?: false)
    }

    @Test
    fun `getArgumentsGRTConstructor with no primary constructor throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidArgumentsNoConstructor::class.getArgumentsGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getArgumentsGRTConstructor with wrong parameter types throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidArgumentsWrongParams::class.getArgumentsGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getArgumentsGRTConstructor with two parameters throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidArgumentsTwoParams::class.getArgumentsGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }

    @Test
    fun `getArgumentsGRTConstructor with four parameters throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            InvalidArgumentsFourParams::class.getArgumentsGRTConstructor()
        }
        assertTrue(exception.message?.contains("Primary constructor") ?: false)
    }
}
