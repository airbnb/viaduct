package viaduct.java.runtime.bridge

import graphql.Scalars
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.Query
import viaduct.java.runtime.bootstrap.JavaResolverClassFinder
import viaduct.service.api.spi.TenantCodeInjector

class JavaModuleBootstrapperTest {
    // Test fixtures
    interface TestQuery : Query

    @ResolverFor(typeName = "TestType", fieldName = "testField")
    abstract class TestResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput>

    @Resolver
    class TestResolver : TestResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("test result")
        }
    }

    @Test
    fun `fieldResolverExecutors returns empty when no resolvers found`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns emptySet()

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchema()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).isEmpty()
    }

    @Test
    fun `fieldResolverExecutors skips resolvers for undefined types`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(TestResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        // Schema without TestType
        val schema = createMockSchema()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        // Should skip because TestType doesn't exist in schema
        assertThat(executors).isEmpty()
    }

    @Test
    fun `fieldResolverExecutors registers resolver for valid field`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(TestResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(TestResolver::class.java, TestResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchemaWithTestType()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (coordinate, executor) = executors.first()
        assertThat(coordinate.first).isEqualTo("TestType")
        assertThat(coordinate.second).isEqualTo("testField")
        assertThat(executor.resolverId).isEqualTo("TestType.testField")
    }

    @Test
    fun `nodeResolverExecutors returns empty list`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchema()

        val executors = bootstrapper.nodeResolverExecutors(schema).toList()

        assertThat(executors).isEmpty()
    }

    // Helper to create mock schema
    private fun createMockSchema(): ViaductSchema {
        val graphqlSchema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject()
                    .name("Query")
                    .field(
                        GraphQLFieldDefinition.newFieldDefinition()
                            .name("placeholder")
                            .type(Scalars.GraphQLString)
                    )
                    .build()
            )
            .build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }

    // Helper to create mock schema with TestType
    private fun createMockSchemaWithTestType(): ViaductSchema {
        val testType = GraphQLObjectType.newObject()
            .name("TestType")
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("testField")
                    .type(Scalars.GraphQLString)
            )
            .build()

        val graphqlSchema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject()
                    .name("Query")
                    .field(
                        GraphQLFieldDefinition.newFieldDefinition()
                            .name("placeholder")
                            .type(Scalars.GraphQLString)
                    )
                    .build()
            )
            .additionalType(testType)
            .build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }
}
