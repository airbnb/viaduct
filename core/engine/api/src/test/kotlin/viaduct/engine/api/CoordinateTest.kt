package viaduct.engine.api

import graphql.schema.FieldCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoordinateTest {
    @Test
    fun `gj`() {
        // field coordinate
        assertEquals(
            FieldCoordinates.coordinates("Foo", "x"),
            ("Foo" to "x").gj
        )

        // system coordinate
        assertEquals(
            FieldCoordinates.systemCoordinates("__typename"),
            ("Foo" to "__typename").gj
        )
    }
}
