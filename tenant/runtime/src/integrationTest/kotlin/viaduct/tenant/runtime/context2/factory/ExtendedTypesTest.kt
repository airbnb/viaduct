package viaduct.tenant.runtime.context2.factory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.mocks.MockInternalContext
import viaduct.api.types.Arguments
import viaduct.engine.api.mocks.MockSchema

class ExtendedTypesTest {
    @Test
    fun `ArgumentsType factory with NoArguments returns the NoArguments singleton`() {
        val actual = ArgumentsType(Arguments.NoArguments::class, MockSchema.minimal)
            .makeGRT(MockInternalContext(MockSchema.minimal), emptyMap())

        assertEquals(Arguments.NoArguments, actual)
    }
}
