package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.GRT
import viaduct.java.api.types.Query

class DefaultJavaResolverClassFinderTest {
    // Test fixtures - these are scanned by ClassGraphScanner when scanning the bridge package

    interface TestQuery : Query

    @ResolverFor(typeName = "TestType", fieldName = "testField")
    abstract class TestResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput>

    @Resolver
    class TestResolverImpl : TestResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("test")
        }
    }

    // Test GRT class for grtClassForName test
    class TestGrt : GRT

    // ClassFinder configured to scan this test's package
    private val classFinder = DefaultJavaResolverClassFinder(
        tenantPackage = "viaduct.java.runtime.bridge",
        grtPackagePrefix = "viaduct.java.runtime.bridge"
    )

    @Test
    fun `resolverClassesInPackage finds classes with ResolverFor annotation`() {
        val resolverClasses = classFinder.resolverClassesInPackage()

        // Should find TestResolverBase which has @ResolverFor
        assertThat(resolverClasses).isNotEmpty
        assertThat(resolverClasses).anyMatch {
            it.name.contains("TestResolverBase")
        }
    }

    @Test
    fun `nodeResolverForClassesInPackage returns empty set`() {
        // Node resolvers are not yet implemented
        val nodeResolverClasses = classFinder.nodeResolverForClassesInPackage()

        assertThat(nodeResolverClasses).isEmpty()
    }

    @Test
    fun `getSubTypesOf finds subclasses of FieldResolverBase`() {
        val subTypes = classFinder.getSubTypesOf(FieldResolverBase::class.java)

        // Should find TestResolverImpl which extends TestResolverBase
        assertThat(subTypes).isNotEmpty
        assertThat(subTypes).anyMatch {
            it.name.contains("TestResolverImpl")
        }
    }

    @Test
    fun `getSubTypesOf finds abstract base classes`() {
        val subTypes = classFinder.getSubTypesOf(FieldResolverBase::class.java)

        // TestResolverBase implements FieldResolverBase
        assertThat(subTypes).anyMatch {
            it.name.contains("TestResolverBase")
        }
    }

    @Test
    fun `grtClassForName loads GRT class by type name`() {
        // Use nested class name format for inner classes
        val grtClass = classFinder.grtClassForName("DefaultJavaResolverClassFinderTest\$TestGrt")

        assertThat(grtClass.name).isEqualTo(
            "viaduct.java.runtime.bridge.DefaultJavaResolverClassFinderTest\$TestGrt"
        )
    }

    @Test
    fun `grtClassForName throws ClassNotFoundException for unknown type`() {
        assertThatThrownBy { classFinder.grtClassForName("NonExistentType") }
            .isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test
    fun `grtClassForName throws IllegalArgumentException for non-GRT class`() {
        // Create a finder that points to a package with non-GRT classes
        val badFinder = DefaultJavaResolverClassFinder(
            tenantPackage = "viaduct.java.runtime.bridge",
            grtPackagePrefix = "java.lang" // String exists but is not a GRT
        )

        assertThatThrownBy { badFinder.grtClassForName("String") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not implement GRT")
    }

    @Test
    fun `argumentClassForName throws ClassNotFoundException for unknown class`() {
        assertThatThrownBy { classFinder.argumentClassForName("NonExistentArgs") }
            .isInstanceOf(ClassNotFoundException::class.java)
    }
}
