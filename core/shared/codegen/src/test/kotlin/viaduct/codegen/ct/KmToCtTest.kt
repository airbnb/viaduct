package viaduct.codegen.ct

import javassist.ClassPool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KmToCtTest {
    @Test
    fun `CtClass_makeNestedClassFixed -- deeply nested`() {
        val cp = ClassPool(true)
        val root = cp.makeClass("Root")
        val a = root.makeNestedClassFixed("A", 0)
        val b = root.makeNestedClassFixed("B", 0)

        a.makeNestedClassFixed("AA", 0)
        b.makeNestedClassFixed("BA", 0)

        // containing classes should contain edges for each directly nested class
        assertEquals(
            setOf(
                NestEdge(a, root, "A", 25),
                NestEdge(b, root, "B", 25),
            ),
            root.nestedEdges.toSet()
        )
    }
}
