package viaduct.tenant.codegen.bytecode.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.codegen.utils.KmName

class InputTypeFactoryConfigTest {
    @Test
    fun `getFactoryMethodName with KmName returns argumentsInputType for Arguments`() {
        val result = InputTypeFactoryConfig.getFactoryMethodName(cfg.ARGUMENTS_GRT.asKmName)
        assertEquals("argumentsInputType", result)
    }

    @Test
    fun `getFactoryMethodName with KmName returns inputObjectInputType for Input`() {
        val result = InputTypeFactoryConfig.getFactoryMethodName(cfg.INPUT_GRT.asKmName)
        assertEquals("inputObjectInputType", result)
    }

    @Test
    fun `getFactoryMethodName with KmName throws for unknown interface`() {
        val unknownKmName = KmName("unknown/interface")
        val exception = assertThrows<IllegalStateException> {
            InputTypeFactoryConfig.getFactoryMethodName(unknownKmName)
        }
        assertEquals("Unknown input tagging interface: $unknownKmName", exception.message)
    }

    @Test
    fun `getFactoryMethodName with String returns argumentsInputType for Arguments`() {
        val result = InputTypeFactoryConfig.getFactoryMethodName("viaduct.api.types.Arguments")
        assertEquals("argumentsInputType", result)
    }

    @Test
    fun `getFactoryMethodName with String returns inputObjectInputType for Input`() {
        val result = InputTypeFactoryConfig.getFactoryMethodName("viaduct.api.types.Input")
        assertEquals("inputObjectInputType", result)
    }

    @Test
    fun `getFactoryMethodName with String throws for unknown interface`() {
        val exception = assertThrows<IllegalStateException> {
            InputTypeFactoryConfig.getFactoryMethodName("unknown.interface")
        }
        assertEquals("Unknown tagging interface: unknown.interface", exception.message)
    }
}
