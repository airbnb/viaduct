package viaduct.api.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MutationFromAnnotationTest {
    @GraphQLOperation("mutation { sendMessage(input: \$input) { success } }")
    object SendMessageMutation : MutationFromAnnotation()

    object MissingAnnotationMutation : MutationFromAnnotation()

    @Test
    fun `operationText returns the GraphQLOperation value`() {
        assertEquals(
            "mutation { sendMessage(input: \$input) { success } }",
            SendMessageMutation.operationText,
        )
    }

    @Test
    fun `operationText throws IllegalStateException when annotation is missing`() {
        val ex = assertThrows<IllegalStateException> { MissingAnnotationMutation.operationText }
        assertEquals(
            "${MissingAnnotationMutation::class.simpleName} must be annotated with @GraphQLOperation",
            ex.message,
        )
    }
}
