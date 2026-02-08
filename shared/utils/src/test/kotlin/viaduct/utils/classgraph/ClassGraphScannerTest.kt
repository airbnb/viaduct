package viaduct.utils.classgraph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClassGraphScannerTest {
    private val classGraphScanner = ClassGraphScanner(listOf("viaduct.utils.classgraph"))

    // Nested test classes
    open class TestBaseClass

    class TestSubClass : TestBaseClass()

    interface TestInterface

    class TestInterfaceImpl : TestInterface

    @Retention(AnnotationRetention.RUNTIME)
    annotation class TestAnnotation

    @TestAnnotation
    class AnnotatedTestClass

    @Test
    fun `test getSubTypesOf for classes`() {
        val result = classGraphScanner.getSubTypesOf(TestBaseClass::class.java)
        assertEquals(setOf(TestSubClass::class.java), result)
    }

    @Test
    fun `test getSubTypesOf for interfaces`() {
        val result = classGraphScanner.getSubTypesOf(TestInterface::class.java)
        assertEquals(setOf(TestInterfaceImpl::class.java), result)
    }

    @Test
    fun `test getTypesAnnotatedWith`() {
        val annotation = TestAnnotation::class.java
        val result = classGraphScanner.getTypesAnnotatedWith(annotation)
        assertEquals(setOf(AnnotatedTestClass::class.java), result)
    }

    @Test
    fun `test getSubTypesOf with package filter`() {
        val result = classGraphScanner.getSubTypesOf(
            TestBaseClass::class.java,
            packagesFilter = setOf("viaduct.utils.classgraph")
        )
        assertEquals(setOf(TestSubClass::class.java), result)
    }

    @Test
    fun `test getSubTypesOf with non-matching package filter returns empty`() {
        val result = classGraphScanner.getSubTypesOf(
            TestBaseClass::class.java,
            packagesFilter = setOf("com.nonexistent.package")
        )
        assertEquals(emptySet<Class<*>>(), result)
    }
}
