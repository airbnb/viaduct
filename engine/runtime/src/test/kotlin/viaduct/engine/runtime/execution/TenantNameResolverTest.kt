package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TenantNameResolverTest {
    private lateinit var tenantNameResolver: TenantNameResolver
    private lateinit var typeName: String
    private lateinit var fieldName: String

    @BeforeEach
    fun setUp() {
        tenantNameResolver = TenantNameResolver()
        typeName = "TestType"
        fieldName = "TestField"
    }

    @Test
    fun `test resolve method returns null`() {
        val result = tenantNameResolver.resolve(typeName, fieldName)
        assertNull(result)
    }
}
