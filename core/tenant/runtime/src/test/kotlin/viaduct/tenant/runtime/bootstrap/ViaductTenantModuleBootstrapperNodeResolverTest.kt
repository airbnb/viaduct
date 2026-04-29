package viaduct.tenant.runtime.bootstrap

import io.mockk.every
import io.mockk.mockk
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.NodeResolverBase
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.bootstrap.test.grts.TestNode
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.TenantModuleException
import viaduct.service.api.spi.TenantCodeInjector

class ViaductTenantModuleBootstrapperNodeResolverTest {
    private val schema = MockSchema.mk(
        """
        type TestNode implements Node @resolver {
            id: ID!
        }
        """.trimIndent()
    )

    @NodeResolverFor("TestNode", isSelective = false, isBatching = false)
    abstract class TestBase : NodeResolverBase<TestNode> {
        open suspend fun resolve(ctx: Context): TestNode = TODO()

        class Context(
            private val inner: NodeExecutionContext<TestNode>
        ) : NodeExecutionContext<TestNode> by inner
    }

    // Base without @NodeResolverFor — invisible to the KSP extractor, so test
    // fixtures that carry deliberately invalid @Resolver params won't cause a
    // compile-time error.
    abstract class UnannotatedBase : NodeResolverBase<TestNode> {
        open suspend fun resolve(ctx: Context): TestNode = TODO()

        class Context(
            private val inner: NodeExecutionContext<TestNode>
        ) : NodeExecutionContext<TestNode> by inner
    }

    @Resolver
    class ActiveResolver : UnannotatedBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    class DraftResolver : UnannotatedBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    @Resolver(objectValueFragment = "fragment _ on TestNode { id }")
    class ResolverWithObjectFragment : UnannotatedBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    @Resolver(queryValueFragment = "fragment _ on Query { viewer { id } }")
    class ResolverWithQueryFragment : UnannotatedBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    @Resolver(variables = [Variable("x", fromArgument = "x")])
    class ResolverWithVariables : UnannotatedBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    @Resolver
    class SecondActiveResolver : UnannotatedBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    private fun bootstrapper(
        nodeResolverForClasses: Set<Class<*>>,
        subTypesOf: Set<Class<out NodeResolverBase<*>>>,
    ): ViaductTenantModuleBootstrapper {
        val classFinder = mockk<TenantResolverClassFinder> {
            every { nodeResolverForClassesInPackage() } returns nodeResolverForClasses
            every { getSubTypesOf(any<Class<NodeResolverBase<*>>>()) } returns subTypesOf
            every { grtClassForName(any()) } answers {
                val name = firstArg<String>()
                @Suppress("UNCHECKED_CAST")
                if (name.endsWith("\$Reflection")) {
                    TestNode.Reflection::class as KClass<ObjectBase>
                } else {
                    TestNode::class as KClass<ObjectBase>
                }
            }
            every { tenantModuleMetadata() } returns TenantModuleMetadata.EMPTY
            every { resolverClassesInPackage() } returns emptySet()
        }
        return ViaductTenantModuleBootstrapper(
            tenantCodeInjector = TenantCodeInjector.Naive,
            tenantResolverClassFinder = classFinder,
            grtConvFactory = DefaultGRTConvFactory,
        )
    }

    @Test
    fun `should register node resolver when exactly one subclass has @Resolver`() {
        val executors = bootstrapper(
            nodeResolverForClasses = setOf(TestBase::class.java),
            subTypesOf = setOf(ActiveResolver::class.java, DraftResolver::class.java),
        ).nodeResolverExecutors(schema).toList()

        assert(executors.size == 1)
        assert(executors.single().first == "TestNode")
    }

    @Test
    fun `should throw when subclasses exist but none has @Resolver`() {
        val exception = assertThrows<TenantModuleException> {
            bootstrapper(
                nodeResolverForClasses = setOf(TestBase::class.java),
                subTypesOf = setOf(DraftResolver::class.java),
            ).nodeResolverExecutors(schema).toList()
        }
        assert(exception.message!!.contains("none are annotated with @Resolver"))
        assert(exception.message!!.contains("DraftResolver"))
    }

    @Test
    fun `should throw when multiple subclasses have @Resolver`() {
        val exception = assertThrows<TenantModuleException> {
            bootstrapper(
                nodeResolverForClasses = setOf(TestBase::class.java),
                subTypesOf = setOf(ActiveResolver::class.java, SecondActiveResolver::class.java),
            ).nodeResolverExecutors(schema).toList()
        }
        assert(exception.message!!.contains("at most one @Resolver-annotated"))
        assert(exception.message!!.contains("found 2"))
    }

    @Test
    fun `should throw when @Resolver specifies objectValueFragment`() {
        val exception = assertThrows<TenantModuleException> {
            bootstrapper(
                nodeResolverForClasses = setOf(TestBase::class.java),
                subTypesOf = setOf(ResolverWithObjectFragment::class.java),
            ).nodeResolverExecutors(schema).toList()
        }
        assert(exception.message!!.contains("objectValueFragment"))
        assert(exception.message!!.contains("do not support required selection sets"))
    }

    @Test
    fun `should throw when @Resolver specifies queryValueFragment`() {
        val exception = assertThrows<TenantModuleException> {
            bootstrapper(
                nodeResolverForClasses = setOf(TestBase::class.java),
                subTypesOf = setOf(ResolverWithQueryFragment::class.java),
            ).nodeResolverExecutors(schema).toList()
        }
        assert(exception.message!!.contains("queryValueFragment"))
        assert(exception.message!!.contains("do not support required selection sets"))
    }

    @Test
    fun `should throw when @Resolver specifies variables`() {
        val exception = assertThrows<TenantModuleException> {
            bootstrapper(
                nodeResolverForClasses = setOf(TestBase::class.java),
                subTypesOf = setOf(ResolverWithVariables::class.java),
            ).nodeResolverExecutors(schema).toList()
        }
        assert(exception.message!!.contains("variables"))
        assert(exception.message!!.contains("do not support required selection sets"))
    }
}
