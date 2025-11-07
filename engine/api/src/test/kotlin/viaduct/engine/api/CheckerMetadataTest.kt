package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckerMetadataTest {
    @Test
    fun `toTagString with fieldName`() {
        val metadata = CheckerMetadata(
            checkerName = "Himeji",
            typeName = "User",
            fieldName = "email"
        )

        assertEquals("Himeji:User.email", metadata.toTagString())
    }

    @Test
    fun `toTagString without fieldName`() {
        val metadata = CheckerMetadata(
            checkerName = "Gandalf",
            typeName = "Query"
        )

        assertEquals("Gandalf:Query", metadata.toTagString())
    }
}
