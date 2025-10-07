package viaduct.tenant.runtime.context2.factory

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest

class ExtendedTypesTest {
    @Test
    fun `ArgumentsType constructor with NoArguments throws IllegalArgumentException`() {
        // This test verifies that trying to construct an ArgumentsType with Arguments.NoArguments fails
        // during ArgumentsType construction due to getArgumentsGRTConstructor validation
        val exception = assertThrows<IllegalArgumentException> {
            ArgumentsType(Arguments.NoArguments::class, GlobalIdFeatureAppTest.schema)
        }

        assertTrue(
            exception.message?.contains("NoArguments") ?: false,
            "Error message should mention NoArguments validation: ${exception.message}"
        )
    }
}
