package viaduct.api.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class FragmentFromAnnotationTest {
    @GraphQLFragment("fragment UserFields on User { id name email }")
    object UserFieldsFragment : FragmentFromAnnotation<CompositeOutput.NotComposite>()

    object MissingAnnotationFragment : FragmentFromAnnotation<CompositeOutput.NotComposite>()

    @Test
    fun `fragmentText returns the GraphQLFragment value`() {
        assertEquals("fragment UserFields on User { id name email }", UserFieldsFragment.fragmentText)
    }

    @Test
    fun `fragmentText throws IllegalStateException when annotation is missing`() {
        val ex = assertThrows<IllegalStateException> { MissingAnnotationFragment.fragmentText }
        assertEquals(
            "${MissingAnnotationFragment::class.simpleName} must be annotated with @GraphQLFragment",
            ex.message,
        )
    }
}
