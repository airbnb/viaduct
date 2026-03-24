package viaduct.engine.runtime.rewriter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.FieldRewriterMetadata
import viaduct.engine.runtime.LateResolvedVariable

class FieldRewriterMetadataTest {
    @Test
    fun `test FieldRewriterMetadata properties`() {
        val prefix = "testPrefix"
        val classPath = "testClassPath"
        val lateResolvedVariables = mapOf("var1" to LateResolvedVariableImpl("var1", "value1"))

        val metadata = FieldRewriterMetadata(prefix, classPath, lateResolvedVariables)

        assertEquals(prefix, metadata.prefix)
        assertEquals(classPath, metadata.classPath)
        assertEquals(lateResolvedVariables, metadata.lateResolvedVariables)
    }

    @Test
    fun `test FieldRewriterMetadata equality`() {
        val prefix = "testPrefix"
        val classPath = "testClassPath"
        val lateResolvedVariables = mapOf("var1" to LateResolvedVariableImpl("var1", "value1"))

        val metadata1 = FieldRewriterMetadata(prefix, classPath, lateResolvedVariables)
        val metadata2 = FieldRewriterMetadata(prefix, classPath, lateResolvedVariables)

        assertEquals(metadata1, metadata2)
    }
}

class LateResolvedVariableImpl(
    private val name: String,
    private val value: String
) : LateResolvedVariable {
    override suspend fun resolve(): Any? {
        return value
    }
}
