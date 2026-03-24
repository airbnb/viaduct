package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ExecutionAttributionTest {
    @Test
    fun `DEFAULT should have null type and name`() {
        assertEquals(null, ExecutionAttribution.DEFAULT.type)
        assertEquals(null, ExecutionAttribution.DEFAULT.name)
    }

    @Test
    fun `fromOperation with name should create OPERATION attribution`() {
        val result = ExecutionAttribution.fromOperation("myOperation")

        assertEquals(ExecutionAttribution.Type.OPERATION, result.type)
        assertEquals("myOperation", result.name)
    }

    @Test
    fun `fromOperation with null name should return DEFAULT`() {
        val result = ExecutionAttribution.fromOperation(null)

        assertSame(ExecutionAttribution.DEFAULT, result)
    }

    @Test
    fun `fromResolver should create RESOLVER attribution`() {
        val result = ExecutionAttribution.fromResolver("myResolver")

        assertEquals(ExecutionAttribution.Type.RESOLVER, result.type)
        assertEquals("myResolver", result.name)
    }

    @Test
    fun `fromPolicyCheck should create POLICY_CHECK attribution`() {
        val result = ExecutionAttribution.fromPolicyCheck("myPolicyCheck")

        assertEquals(ExecutionAttribution.Type.POLICY_CHECK, result.type)
        assertEquals("myPolicyCheck", result.name)
    }

    @Test
    fun `fromVariablesResolver should create VARIABLES_RESOLVER attribution`() {
        val result = ExecutionAttribution.fromVariablesResolver("myVariablesResolver")

        assertEquals(ExecutionAttribution.Type.VARIABLES_RESOLVER, result.type)
        assertEquals("myVariablesResolver", result.name)
    }

    @Test
    fun `toTagString should work with all attribution types`() {
        ExecutionAttribution.Type.values().forEach { type ->
            val name = "testName"
            val tagValue = when (type) {
                ExecutionAttribution.Type.OPERATION -> ExecutionAttribution.fromOperation(name).toTagString()
                ExecutionAttribution.Type.RESOLVER -> ExecutionAttribution.fromResolver(name).toTagString()
                ExecutionAttribution.Type.POLICY_CHECK -> ExecutionAttribution.fromPolicyCheck(name).toTagString()
                ExecutionAttribution.Type.VARIABLES_RESOLVER -> ExecutionAttribution.fromVariablesResolver(name).toTagString()
                else -> throw AssertionError("Unhandled type: $type")
            }
            assertEquals("${type.name}:$name", tagValue)
        }
    }
}
