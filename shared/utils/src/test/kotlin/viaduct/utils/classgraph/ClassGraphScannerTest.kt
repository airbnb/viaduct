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

    @Test
    fun `test invalidateCache does not throw`() {
        val scanner = ClassGraphScanner.forPackagePrefix("viaduct.utils.classgraph")
        // Invalidate cache when cache key is empty should not throw
        ClassGraphScanner.invalidateCache("viaduct.utils.classgraph")

        // Subsequent scan should still work
        val result = scanner.getSubTypesOf(TestBaseClass::class.java)
        assertEquals(setOf(TestSubClass::class.java), result)
    }

    @Test
    fun `invalidateCache evicts entries whose key is equal to or a parent package of the given prefix`() {
        // Exact match and parent prefix scanner should be evicted
        val exactMatch = ClassGraphScanner.forPackagePrefix("viaduct.utils.classgraph")
        val parentPrefix = ClassGraphScanner.forPackagePrefix("viaduct.utils")

        // Child prefix, unrelated, and similar-string-without-dot-boundary should NOT be evicted
        val childPrefix = ClassGraphScanner.forPackagePrefix("viaduct.utils.classgraph.subpkg")
        val unrelated = ClassGraphScanner.forPackagePrefix("com.other.package")
        val similarNoDot = ClassGraphScanner.forPackagePrefix("viaduct.utils.classgraphextended")

        val exactBefore = exactMatch.getScanResult()
        val parentBefore = parentPrefix.getScanResult()
        val childBefore = childPrefix.getScanResult()
        val unrelatedBefore = unrelated.getScanResult()
        val similarBefore = similarNoDot.getScanResult()

        ClassGraphScanner.invalidateCache("viaduct.utils.classgraph")

        assert(exactMatch.getScanResult() !== exactBefore) { "exact match should be evicted" }
        assert(parentPrefix.getScanResult() !== parentBefore) { "parent prefix should be evicted" }
        assert(childPrefix.getScanResult() === childBefore) { "child prefix should not be evicted" }
        assert(unrelated.getScanResult() === unrelatedBefore) { "unrelated prefix should not be evicted" }
        assert(similarNoDot.getScanResult() === similarBefore) { "similar string without dot boundary should not be evicted" }
    }

    @Test
    fun `test forPackagePrefix creates new scanner instance`() {
        val scanner1 = ClassGraphScanner.forPackagePrefix("viaduct.utils.classgraph")
        val scanner2 = ClassGraphScanner.forPackagePrefix("viaduct.utils.classgraph")

        // Each call should create a new instance
        assert(scanner1 !== scanner2)
    }

    @Test
    fun `test optimizedForPackagePrefix returns INSTANCE for default packages`() {
        val instance = ClassGraphScanner.INSTANCE

        // optimizedForPackagePrefix should return INSTANCE for packages covered by default prefixes
        // Default prefixes include: "viaduct.tenant", "viaduct.engine", "viaduct.api"
        val optimizedScanner = ClassGraphScanner.optimizedForPackagePrefix("viaduct.tenant.somepackage")
        assertEquals(instance, optimizedScanner)
    }

    @Test
    fun `test optimizedForPackagePrefix returns new scanner for unknown packages`() {
        val instance = ClassGraphScanner.INSTANCE

        // optimizedForPackagePrefix should return a new scanner for packages NOT covered by default prefixes
        val optimizedScanner = ClassGraphScanner.optimizedForPackagePrefix("com.unknown.package")
        assert(optimizedScanner !== instance)
    }
}
