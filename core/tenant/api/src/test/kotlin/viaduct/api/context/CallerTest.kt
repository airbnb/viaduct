package viaduct.api.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallerTest {
    @Test
    fun `coordinateString formats the GraphQL field coordinate`() {
        val caller = Caller(
            tenantName = "example-tenant",
            typeName = "Query",
            fieldName = "listing",
        )

        assertEquals("Query.listing", caller.coordinateString)
    }

    @Test
    fun `coordinateString formats a type-level caller`() {
        val caller = Caller(
            tenantName = "example-tenant",
            typeName = "Listing",
            fieldName = null,
        )

        assertEquals("Listing", caller.coordinateString)
    }
}
