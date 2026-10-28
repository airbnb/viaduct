package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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

    @Test
    fun `isRemote defaults to false`() {
        val metadata = ResolverMetadata.forModern("User.name")

        assertEquals(false, metadata.isRemote)
    }

    @Test
    fun `toTagString returns the same instance on every call and follows copies`() {
        val metadata = ResolverMetadata.forModern("User.name")

        assertSame(metadata.toTagString(), metadata.toTagString())
        assertEquals("modern:User.email", metadata.copy(name = "User.email").toTagString())
    }
}
