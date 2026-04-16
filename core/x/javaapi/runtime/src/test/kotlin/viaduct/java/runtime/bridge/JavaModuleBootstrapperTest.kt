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

    @ResolverFor(typeName = "TestType", fieldName = "testField", isSelective = false)
    abstract class TestResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @ResolverFor(typeName = "TestType", fieldName = "selectiveField", isSelective = true)
    abstract class SelectiveResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @Resolver
    class TestResolver : TestResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("test result")
        }
    }

    @Resolver
    class SelectiveResolver : SelectiveResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("selective result")
        }
    }

    // Test fixtures for required selections tests
    @ResolverFor(typeName = "Person", fieldName = "fullName", isSelective = false)
    abstract class PersonFullNameResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @Resolver(objectValueFragment = "firstName lastName")
    class PersonFullNameResolver : PersonFullNameResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("Full Name")
        }
    }

    // Test fixture for resolver without required selections (plain @Resolver)
    @ResolverFor(typeName = "Person", fieldName = "age", isSelective = false)
    abstract class PersonAgeResolverBase :
        FieldResolverBase<Int, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<Int>
    }

    @Resolver
    class PersonAgeResolver : PersonAgeResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<Int> {
            return CompletableFuture.completedFuture(30)
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
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(
            TestResolverBase::class.java,
            SelectiveResolverBase::class.java
        )
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(
                TestResolver::class.java,
                TestResolverBase::class.java,
                SelectiveResolver::class.java,
                SelectiveResolverBase::class.java
            )

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchemaWithTestType()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(2)
        val executorsByCoordinate = executors.toMap()
        assertThat(executorsByCoordinate.getValue("TestType" to "testField").resolverId).isEqualTo("TestType.testField")
        assertThat(executorsByCoordinate.getValue("TestType" to "testField").isSelective).isFalse()
        assertThat(executorsByCoordinate.getValue("TestType" to "selectiveField").resolverId).isEqualTo("TestType.selectiveField")
        assertThat(executorsByCoordinate.getValue("TestType" to "selectiveField").isSelective).isTrue()
    }

    @Test
    fun `nodeResolverExecutors returns empty list`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchema()

        val executors = bootstrapper.nodeResolverExecutors(schema).toList()

        assertThat(executors).isEmpty()
    }

    @Test
    fun `fieldResolverExecutors creates executor with objectSelectionSet from annotation`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(PersonFullNameResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(PersonFullNameResolver::class.java, PersonFullNameResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchemaWithPerson()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (coordinate, executor) = executors.first()
        assertThat(coordinate.first).isEqualTo("Person")
        assertThat(coordinate.second).isEqualTo("fullName")
        // The executor should have objectSelectionSet populated from the annotation
        assertThat(executor.objectSelectionSet).isNotNull
        assertThat(executor.querySelectionSet).isNull()
    }

    @Test
    fun `fieldResolverExecutors creates executor with empty selections for plain Resolver annotation`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(PersonAgeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(PersonAgeResolver::class.java, PersonAgeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, TenantCodeInjector.Naive)
        val schema = createMockSchemaWithPerson()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (_, executor) = executors.first()
        // Plain @Resolver annotation should result in null selection sets (backward compatible)
        assertThat(executor.objectSelectionSet).isNull()
        assertThat(executor.querySelectionSet).isNull()
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
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("selectiveField")
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

    // Helper to create mock schema with Person type
    private fun createMockSchemaWithPerson(): ViaductSchema {
        val personType = GraphQLObjectType.newObject()
            .name("Person")
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("firstName")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("lastName")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("fullName")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("age")
                    .type(Scalars.GraphQLInt)
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
            .additionalType(personType)
            .build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }
}
