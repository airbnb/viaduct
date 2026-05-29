package viaduct.api.documents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class GraphQLOperationAnnotationTest {
    @Test
    fun `GraphQLOperation targets CLASS only`() {
        val target = GraphQLOperation::class.annotations.filterIsInstance<Target>().firstOrNull()
        assertNotNull(target)
        assertEquals(listOf(AnnotationTarget.CLASS), target!!.allowedTargets.toList())
    }

    @Test
    fun `GraphQLOperation has RUNTIME retention`() {
        val retention = GraphQLOperation::class.annotations.filterIsInstance<Retention>().firstOrNull()
        assertNotNull(retention)
        assertEquals(AnnotationRetention.RUNTIME, retention!!.value)
    }
}
