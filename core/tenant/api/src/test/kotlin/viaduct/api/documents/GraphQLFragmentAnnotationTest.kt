package viaduct.api.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class GraphQLFragmentAnnotationTest {
    @GraphQLFragment("fragment TestFragment on TestType { id }")
    object TestFragmentObject : FragmentFromAnnotation<CompositeOutput.NotComposite>()

    @Test
    fun `GraphQLFragment targets CLASS only`() {
        val target = GraphQLFragment::class.annotations.filterIsInstance<Target>().firstOrNull()
        assertNotNull(target)
        assertEquals(listOf(AnnotationTarget.CLASS), target!!.allowedTargets.toList())
    }

    @Test
    fun `GraphQLFragment has RUNTIME retention`() {
        val retention = GraphQLFragment::class.annotations.filterIsInstance<Retention>().firstOrNull()
        assertNotNull(retention)
        assertEquals(AnnotationRetention.RUNTIME, retention!!.value)
    }

    @Test
    fun `GraphQLFragment value is accessible on annotated object`() {
        assertEquals("fragment TestFragment on TestType { id }", TestFragmentObject.fragmentText)
    }
}
