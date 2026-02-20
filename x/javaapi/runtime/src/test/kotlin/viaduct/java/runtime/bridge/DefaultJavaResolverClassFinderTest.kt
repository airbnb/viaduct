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
    fun `grtClassForName throws UnsupportedOperationException`() {
        assertThatThrownBy { classFinder.grtClassForName("SomeType") }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("GRT class loading is not yet supported")
    }

    @Test
    fun `argumentClassForName throws UnsupportedOperationException`() {
        assertThatThrownBy { classFinder.argumentClassForName("SomeArgs") }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("Arguments class loading is not yet supported")
    }
}
