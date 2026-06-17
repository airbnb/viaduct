package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.GRT
import viaduct.java.api.types.Query

class DefaultResolverClassFinderTest {
    // Test fixtures - these are scanned by ClassGraphScanner when scanning the bridge package

    interface TestQuery : Query

    @ResolverFor(typeName = "TestType", fieldName = "testField", isSelective = false)
    abstract class TestResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        // Define resolve method on the base class (mirrors generated code pattern)
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @Resolver
    class TestResolverImpl : TestResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("test")
        }
    }

    // Test GRT class for grtClassForName test
    class TestGrt : GRT

    // ClassFinder configured to scan this test's package
    private val classFinder = DefaultResolverClassFinder(
        tenantPackage = "viaduct.java.runtime.bridge",
        grtPackagePrefix = "viaduct.java.runtime.bridge"
    )

    @Test
    fun `resolverClassesInPackage finds classes with ResolverFor annotation`() {
        val resolverClasses = classFinder.resolverClassesInPackage()

        // Should find TestResolverBase which has @ResolverFor
        assertTrue(resolverClasses.isNotEmpty())
        assertTrue(resolverClasses.any { it.name.contains("TestResolverBase") })
    }

    @Test
    fun `nodeResolverForClassesInPackage finds NodeResolverFor annotated classes`() {
        val nodeResolverClasses = classFinder.nodeResolverForClassesInPackage()

        // Should not include field resolver bases (which have @ResolverFor, not @NodeResolverFor)
        assertTrue(nodeResolverClasses.none { it.name.contains("TestResolverBase") })
    }

    @Test
    fun `getSubTypesOf finds subclasses of FieldResolverBase`() {
        val subTypes = classFinder.getSubTypesOf(FieldResolverBase::class.java)

        // Should find TestResolverImpl which extends TestResolverBase
        assertTrue(subTypes.isNotEmpty())
        assertTrue(subTypes.any { it.name.contains("TestResolverImpl") })
    }

    @Test
    fun `getSubTypesOf finds abstract base classes`() {
        val subTypes = classFinder.getSubTypesOf(FieldResolverBase::class.java)

        // TestResolverBase implements FieldResolverBase
        assertTrue(subTypes.any { it.name.contains("TestResolverBase") })
    }

    @Test
    fun `grtClassForName loads GRT class by type name`() {
        // Use nested class name format for inner classes
        val grtClass = classFinder.grtClassForName("DefaultResolverClassFinderTest\$TestGrt")

        assertEquals(
            "viaduct.java.runtime.bridge.DefaultResolverClassFinderTest\$TestGrt",
            grtClass.name
        )
    }

    @Test
    fun `grtClassForName throws ClassNotFoundException for unknown type`() {
        assertThrows<ClassNotFoundException> { classFinder.grtClassForName("NonExistentType") }
    }

    @Test
    fun `grtClassForName throws IllegalArgumentException for non-GRT class`() {
        // Create a finder that points to a package with non-GRT classes
        val badFinder = DefaultResolverClassFinder(
            tenantPackage = "viaduct.java.runtime.bridge",
            grtPackagePrefix = "java.lang" // String exists but is not a GRT
        )

        val e = assertThrows<IllegalArgumentException> { badFinder.grtClassForName("String") }
        assertTrue(e.message!!.contains("does not implement GRT"))
    }

    @Test
    fun `argumentClassForName throws ClassNotFoundException for unknown class`() {
        assertThrows<ClassNotFoundException> { classFinder.argumentClassForName("NonExistentArgs") }
    }
}
