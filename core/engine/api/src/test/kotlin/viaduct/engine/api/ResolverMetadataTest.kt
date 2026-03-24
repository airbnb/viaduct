package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResolverMetadataTest {
    @Test
    fun `toTagString returns flavor colon name`() {
        val metadata = ResolverMetadata.forModern("User.name")

        assertEquals("modern:User.name", metadata.toTagString())
    }

    @Test
    fun `toTagString for mock resolver includes mock as flavor`() {
        val metadata = ResolverMetadata.forMock("mock-field-unbatched-resolver")

        assertEquals("mock:mock-field-unbatched-resolver", metadata.toTagString())
    }
}
