package viaduct.tenant.runtime.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.errors.TenantResolverException

class ViaductTenantNameResolverExceptionsTest {
    @Test
    fun testResolversCallChain() {
        val exception = TenantResolverException(RuntimeException("foo"), "Pet.name")
        assertEquals("Pet.name", exception.resolversCallChain)

        val outerException = TenantResolverException(exception, "Person.pet")
        assertEquals("Person.pet > Pet.name", outerException.resolversCallChain)
    }
}
