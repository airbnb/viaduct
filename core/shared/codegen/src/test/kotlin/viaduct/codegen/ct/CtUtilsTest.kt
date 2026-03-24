package viaduct.codegen.ct

import javassist.ClassPool
import javassist.bytecode.InnerClassesAttribute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CtUtilsTest {
    private val cp = ClassPool(true)
    private val root = cp.makeClass("Root")
    private val a = root.makeNestedClassFixed("A", 0)
    private val aa = a.makeNestedClassFixed("AA", 0)
    private val ab = a.makeNestedClassFixed("AB", 0)
    private val b = root.makeNestedClassFixed("B", 0)

    @Test
    fun `CtClass_innerClassesAttribute`() {
        ClassPool(true).makeClass("Subject").also {
            assertNull(it.innerClassesAttribute)
        }

        ClassPool(true).makeClass("Subject").also {
            val cf = it.classFile
            val attr = InnerClassesAttribute(cf.constPool)
            cf.addAttribute(attr)

            assertEquals(attr, it.innerClassesAttribute)
        }
    }

    @Test
    fun `CtClass_nestEdges`() {
        assertEquals(
            listOf(
                NestEdge(a, root, "A", 25),
                NestEdge(b, root, "B", 25),
            ),
            root.nestEdges
        )

        assertEquals(
            listOf(
                NestEdge(a, root, "A", 25),
                NestEdge(aa, a, "AA", 25),
                NestEdge(ab, a, "AB", 25),
            ),
            a.nestEdges
        )
    }

    @Test
    fun `CtClass_nestingEdge`() {
        assertNull(root.nestingEdge)
        assertEquals(NestEdge(a, root, "A", 25), a.nestingEdge)
        assertEquals(NestEdge(aa, a, "AA", 25), aa.nestingEdge)
    }

    @Test
    fun `CtClass_nestedEdges`() {
        assertEquals(
            listOf(
                NestEdge(a, root, "A", 25),
                NestEdge(b, root, "B", 25),
            ),
            root.nestedEdges
        )

        assertEquals(
            listOf(
                NestEdge(aa, a, "AA", 25),
                NestEdge(ab, a, "AB", 25),
            ),
            a.nestedEdges
        )
        assertEquals(emptyList<NestEdge>(), aa.nestedEdges)
        assertEquals(emptyList<NestEdge>(), ab.nestedEdges)
        assertEquals(emptyList<NestEdge>(), b.nestedEdges)
    }

    @Test
    fun `NestEdge_write`() {
        val pool = ClassPool(true)
        val nesting = pool.makeClass("pkg.Outer")
        val nested = pool.makeClass("pkg.Inner")
        val edge = NestEdge(nested, nesting, "Inner", 25)

        val ica = InnerClassesAttribute(nesting.classFile.constPool)
        // sanity
        assertEquals(0, ica.tableLength())

        edge.write(ica)
        assertEquals(1, ica.tableLength())
        assertEquals(nesting.name, ica.outerClass(0))
        assertEquals(nested.name, ica.innerClass(0))
        assertEquals("Inner", ica.innerName(0))
        assertEquals(25, ica.accessFlags(0))
    }
}
