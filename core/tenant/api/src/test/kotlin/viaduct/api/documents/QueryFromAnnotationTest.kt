package viaduct.api.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryFromAnnotationTest {
    @GraphQLOperation("{ viewer { id name } }")
    object GetViewerQuery : QueryFromAnnotation()

    object MissingAnnotationQuery : QueryFromAnnotation()

    @Test
    fun `operationText returns the GraphQLOperation value`() {
        assertEquals("{ viewer { id name } }", GetViewerQuery.operationText)
    }

    @Test
    fun `operationText throws IllegalStateException when annotation is missing`() {
        val ex = assertThrows<IllegalStateException> { MissingAnnotationQuery.operationText }
        assertEquals(
            "${MissingAnnotationQuery::class.simpleName} must be annotated with @GraphQLOperation",
            ex.message,
        )
    }
}
